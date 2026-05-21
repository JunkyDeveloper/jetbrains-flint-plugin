package com.github.junkydeveloper.jetbrainsflintplugin.uninstall

import com.github.junkydeveloper.jetbrainsflintplugin.run.FlintVizRunConfiguration
import com.github.junkydeveloper.jetbrainsflintplugin.settings.FlintSettings
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import java.nio.file.Files
import java.nio.file.Path
import org.rust.cargo.project.model.CargoProjectsService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-cleanup scheduled when the Flint plugin is uninstalled from the IDE.
 *
 * Stops plugin-launched flint-viz processes, wipes the IDE-global managed dir,
 * detaches the flint-steel Cargo project from RustRover's Cargo panel, and
 * resets persisted settings. Every step is best-effort and idempotent so it is
 * safe to retry.
 */
object FlintUninstaller {

    const val PLUGIN_ID = "com.github.junkydeveloper.jetbrainsflintplugin"

    private val pendingDeleteMarker: Path =
        Path.of(PathManager.getSystemPath(), "flint-plugin.delete-on-start")

    private val cleanupStarted = AtomicBoolean(false)
    private val cleanupLock = Any()
    @Volatile
    private var cleanupFinished = false
    @Volatile
    private var cleanupFuture: Future<*>? = null

    fun isSelf(descriptor: IdeaPluginDescriptor): Boolean =
        descriptor.pluginId.idString == PLUGIN_ID

    fun cleanupAsync() {
        synchronized(cleanupLock) {
            if (cleanupStarted.get() || cleanupFinished) return
            cleanupStarted.set(true)

            cleanupFuture = ApplicationManager.getApplication().executeOnPooledThread {
                runCleanupToCompletion()
            }
        }
    }

    fun cleanupBeforeShutdown() {
        val running = synchronized(cleanupLock) {
            if (!cleanupStarted.get() || cleanupFinished) return
            cleanupFuture
        }

        if (running == null) {
            runCleanupToCompletion()
            return
        }

        val log = thisLogger()
        runCatching {
            running.get(15, TimeUnit.SECONDS)
        }.onFailure {
            log.warn("Timed out waiting for Flint uninstall cleanup before shutdown", it)
        }
    }

    fun retryPendingDeleteAsync() {
        if (!Files.exists(pendingDeleteMarker)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            retryPendingDelete()
        }
    }

    fun cleanup() {
        val log = thisLogger()
        val manifest = FlintSettings.managedFlintSteelDir().resolve("Cargo.toml")
        markPendingDelete()

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue

            stopFlintVizProcesses(project)

            // Detach the flint-steel Cargo project from the Cargo panel.
            runCatching {
                val svc = project.service<CargoProjectsService>()
                val cp = svc.allProjects.firstOrNull { it.manifest == manifest }
                if (cp != null) {
                    runBlockingCancellable { svc.detachCargoProject(cp).await() }
                }
            }.onFailure { log.warn("detachCargoProject failed (continuing)", it) }

            // Reset persisted settings to defaults.
            runCatching {
                FlintSettings.getInstance(project).loadState(FlintSettings.State())
            }.onFailure { log.warn("FlintSettings reset failed (continuing)", it) }
        }

        // Delete the whole IDE-global managed dir
        // (flint-steel + flint-index + flint-viz + .cache).
        deleteManagedDir(log)
    }

    private fun runCleanupToCompletion() {
        try {
            cleanup()
        } finally {
            cleanupFinished = true
        }
    }

    private fun retryPendingDelete() {
        deleteManagedDir(thisLogger())
    }

    private fun markPendingDelete() {
        runCatching {
            Files.writeString(pendingDeleteMarker, FlintSettings.managedFlintPluginDir().toString())
        }.onFailure {
            thisLogger().warn("Failed to write Flint pending-delete marker", it)
        }
    }

    private fun deleteManagedDir(log: com.intellij.openapi.diagnostic.Logger) {
        runCatching {
            val dir = FlintSettings.managedFlintPluginDir().toFile()
            if (dir.exists() && !dir.deleteRecursively()) {
                log.warn("flint-plugin dir delete failed: $dir")
                return
            }
            Files.deleteIfExists(pendingDeleteMarker)
        }.onFailure { log.warn("flint-plugin dir delete failed", it) }
    }

    private fun stopFlintVizProcesses(project: Project) {
        val log = thisLogger()

        runCatching {
            for (handler in ExecutionManager.getInstance(project).getRunningProcesses()) {
                if (handler.getUserData(FlintVizRunConfiguration.FLINT_VIZ_PROCESS_HANDLER) != true) continue
                if (handler.isProcessTerminated || handler.isProcessTerminating) continue

                handler.destroyProcess()
                if (!handler.waitFor(5_000)) {
                    if (handler is KillableProcessHandler && handler.canKillProcess()) {
                        handler.killProcess()
                        handler.waitFor(5_000)
                    }
                    if (!handler.isProcessTerminated) {
                        log.warn("flint-viz did not stop within timeout")
                    }
                }
            }
        }.onFailure { log.warn("flint-viz stop failed (continuing)", it) }
    }
}
