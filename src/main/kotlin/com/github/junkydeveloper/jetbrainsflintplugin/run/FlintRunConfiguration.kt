package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.github.junkydeveloper.jetbrainsflintplugin.services.CargoConfigConflict
import com.github.junkydeveloper.jetbrainsflintplugin.services.CargoConfigWriter
import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintSteelManager
import com.github.junkydeveloper.jetbrainsflintplugin.services.SteelResolution
import com.github.junkydeveloper.jetbrainsflintplugin.services.SteelWorkspaceResolver
import com.github.junkydeveloper.jetbrainsflintplugin.settings.FlintSettings
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import org.jdom.Element
import org.rust.cargo.project.model.CargoProjectsService
import org.rust.cargo.runconfig.createCargoCommandRunConfiguration
import org.rust.cargo.toolchain.CargoCommandLine

enum class FlintMode { SELECTED, ALL }

/** Cargo build profile. `flint` is required for flint-steel to compile. */
enum class FlintProfile(val cargoName: String) {
    FLINT("flint"),
    DEV("dev"),
}

/**
 * Prepares the managed flint-steel clone (checkout, cargo patch, attach) then
 * delegates to the `com.jetbrains.rust` Cargo runner so Run and Debug (native
 * LLDB) come for free. Prep + launch run off the EDT; failures surface as
 * notifications. `getState` returns null — this configuration only delegates.
 */
class FlintRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var mode: FlintMode = FlintMode.SELECTED
    var profile: FlintProfile = FlintProfile.FLINT
    var version: String = "latest"

    /** Optional run-config overrides (blank = inherit menu defaults). */
    var overrideTags: String = ""
    var overrideTest: String = ""
    var overridePattern: String = ""
    var extraEnv: MutableMap<String, String> = linkedMapOf()

    override fun getConfigurationEditor() = FlintRunConfigurationEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                launch(executor)
            } catch (e: Exception) {
                thisLogger().warn("Flint run failed", e)
                notify("Flint run failed", e.message ?: e.toString(), NotificationType.ERROR)
            }
        }
        return null
    }

    private fun launch(executor: Executor) {
        val manager = FlintSteelManager.getInstance(project)
        val settings = FlintSettings.getInstance(project).state

        manager.checkout(version)

        when (val r = SteelWorkspaceResolver.resolve(project.basePath)) {
            is SteelResolution.Failure -> {
                notify("Flint: SteelMC workspace not resolved", r.message, NotificationType.ERROR)
                return
            }
            is SteelResolution.Resolved -> {
                try {
                    CargoConfigWriter.write(manager.managedDir, r.crates, settings.localFlintCorePath)
                } catch (e: CargoConfigConflict) {
                    notify("Flint: .cargo/config.toml conflict", e.message ?: "", NotificationType.ERROR)
                    return
                }
            }
        }

        // Attach the clone as a Cargo project so the Rust plugin indexes it
        // (enables debugging / source mapping). Best-effort.
        runCatching {
            val svc = project.service<CargoProjectsService>()
            val manifest = manager.managedDir.resolve("Cargo.toml")
            runBlockingCancellable { svc.attachCargoProject(manifest).await() }
        }.onFailure { thisLogger().warn("attachCargoProject failed (continuing)", it) }

        val entrypoint = when (mode) {
            FlintMode.SELECTED -> "test_run_flint_selected"
            FlintMode.ALL -> "test_run_all_flint_benchmarks"
        }
        val args = listOf(
            "--lib", entrypoint,
            "--profile", profile.cargoName,
            "--no-fail-fast", "--", "--nocapture",
        )
        val env = EnvironmentVariablesData.create(buildEnv(settings), true)

        val cmd = CargoCommandLine(
            command = "test",
            workingDirectory = manager.managedDir,
            additionalArguments = args,
            environmentVariables = env,
        )

        ApplicationManager.getApplication().invokeLater {
            val runManager = RunManager.getInstance(project)
            val rc = runManager.createCargoCommandRunConfiguration(cmd, "Flint: ${mode.name.lowercase()} @ $version")
            ProgramRunnerUtil.executeConfiguration(rc, executor)
        }
    }

    /** Precedence: run-config override > menu defaults > .env (vars left unset). */
    private fun buildEnv(settings: FlintSettings.State): Map<String, String> {
        val env = FlintSettings.getInstance(project).toEnv().toMutableMap()
        if (mode == FlintMode.ALL) {
            // All mode ignores the filter entirely.
            env.remove("FLINT_TEST"); env.remove("FLINT_TAGS"); env.remove("FLINT_PATTERN")
        } else {
            if (overrideTest.isNotBlank()) env["FLINT_TEST"] = overrideTest
            if (overrideTags.isNotBlank()) env["FLINT_TAGS"] = overrideTags
            if (overridePattern.isNotBlank()) env["FLINT_PATTERN"] = overridePattern
        }
        env.putAll(extraEnv.filterValues { it.isNotBlank() })
        return env
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Flint")
            .createNotification(title, content, type)
            .notify(project)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "mode", mode.name)
        JDOMExternalizerUtil.writeField(element, "profile", profile.name)
        JDOMExternalizerUtil.writeField(element, "version", version)
        JDOMExternalizerUtil.writeField(element, "overrideTags", overrideTags)
        JDOMExternalizerUtil.writeField(element, "overrideTest", overrideTest)
        JDOMExternalizerUtil.writeField(element, "overridePattern", overridePattern)
        JDOMExternalizerUtil.writeField(
            element, "extraEnv",
            extraEnv.entries.joinToString(";") { "${it.key}=${it.value}" },
        )
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        mode = runCatching { FlintMode.valueOf(JDOMExternalizerUtil.readField(element, "mode", "SELECTED")) }
            .getOrDefault(FlintMode.SELECTED)
        profile = runCatching { FlintProfile.valueOf(JDOMExternalizerUtil.readField(element, "profile", "FLINT")) }
            .getOrDefault(FlintProfile.FLINT)
        version = JDOMExternalizerUtil.readField(element, "version", "latest")
        overrideTags = JDOMExternalizerUtil.readField(element, "overrideTags", "")
        overrideTest = JDOMExternalizerUtil.readField(element, "overrideTest", "")
        overridePattern = JDOMExternalizerUtil.readField(element, "overridePattern", "")
        extraEnv = JDOMExternalizerUtil.readField(element, "extraEnv", "")
            .split(";").filter { it.contains("=") }
            .associate { it.substringBefore("=") to it.substringAfter("=") }
            .toMutableMap()
            .let { LinkedHashMap(it) }
    }
}
