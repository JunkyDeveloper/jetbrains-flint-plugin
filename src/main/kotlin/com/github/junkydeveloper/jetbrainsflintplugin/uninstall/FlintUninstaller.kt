package com.github.junkydeveloper.jetbrainsflintplugin.uninstall

import com.github.junkydeveloper.jetbrainsflintplugin.settings.FlintSettings
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.ProjectManager
import org.rust.cargo.project.model.CargoProjectsService

/**
 * Self-cleanup performed when the Flint plugin is uninstalled from the IDE.
 *
 * Wipes the IDE-global managed dir, detaches the flint-steel Cargo project
 * from RustRover's Cargo panel, and resets persisted settings. Every step is
 * best-effort and idempotent so it is safe to fire from multiple uninstall
 * lifecycle hooks.
 */
object FlintUninstaller {

    const val PLUGIN_ID = "com.github.junkydeveloper.jetbrainsflintplugin"

    fun isSelf(descriptor: IdeaPluginDescriptor): Boolean =
        descriptor.pluginId.idString == PLUGIN_ID

    fun cleanup() {
        val log = thisLogger()
        val manifest = FlintSettings.managedFlintSteelDir().resolve("Cargo.toml")

        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue

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
        runCatching {
            val dir = FlintSettings.managedFlintPluginDir().toFile()
            if (dir.exists()) dir.deleteRecursively()
        }.onFailure { log.warn("flint-plugin dir delete failed", it) }
    }
}
