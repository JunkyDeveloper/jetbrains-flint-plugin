package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.github.junkydeveloper.jetbrainsflintplugin.services.CargoConfigConflict
import com.github.junkydeveloper.jetbrainsflintplugin.services.CargoConfigWriter
import com.github.junkydeveloper.jetbrainsflintplugin.services.CloneProfileInjector
import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintSteelManager
import com.github.junkydeveloper.jetbrainsflintplugin.services.InjectResult
import com.github.junkydeveloper.jetbrainsflintplugin.services.SteelResolution
import com.github.junkydeveloper.jetbrainsflintplugin.services.SteelWorkspaceResolver
import com.github.junkydeveloper.jetbrainsflintplugin.settings.FlintSettings
import com.intellij.execution.ExecutionTargetManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
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
import org.rust.cargo.runconfig.profiles.CargoBuildProfile
import org.rust.cargo.runconfig.profiles.RsDefaultProfileExecutionTarget
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
    // Cargo profile is no longer user-selectable: it is derived from the
    // executor in launchFlint (Run -> dev, Debug -> flint).
    var version: String = "latest"

    /** Optional run-config overrides (blank = inherit menu defaults). */
    var overrideTags: String = ""
    var selectedTags: MutableList<String> = mutableListOf()
    var overrideTest: String = ""
    var overridePattern: String = ""
    var extraEnv: MutableMap<String, String> = linkedMapOf()

    override fun getConfigurationEditor() = FlintRunConfigurationEditor(project)

    // Prep + delegation is driven by FlintProgramRunner.execute (it owns both
    // Run and Debug). getState only exists to satisfy the contract; returning
    // null with no side-effect avoids a double-launch.
    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? = null

    fun launchFlint(executor: Executor) {
        val manager = FlintSteelManager.getInstance(project)
        val settings = FlintSettings.getInstance(project).state

        // Run -> dev (built-in, always present), Debug -> flint (needs the
        // SteelMC-defined profile injected into the clone, below).
        val profile = if (executor.id == DefaultDebugExecutor.EXECUTOR_ID) {
            FlintProfile.FLINT
        } else {
            FlintProfile.DEV
        }

        manager.checkout(version)

        when (val r = SteelWorkspaceResolver.resolve(project.basePath)) {
            is SteelResolution.Failure -> {
                notify("Flint: SteelMC workspace not resolved", r.message, NotificationType.ERROR)
                return
            }
            is SteelResolution.Resolved -> {
                // Profiles the clone already declares natively must not be
                // re-emitted (a second [profile.x] is a fatal cargo error,
                // both within Cargo.toml and across Cargo.toml/config.toml).
                val nativeProfiles = CloneProfileInjector.existingProfiles(manager.managedDir)
                val closure = if (profile == FlintProfile.FLINT) {
                    SteelWorkspaceResolver.profileClosure(r.profileBlocks, profile.cargoName)
                } else {
                    emptyMap()
                }
                val toEmit = closure.filterKeys { it !in nativeProfiles }

                try {
                    CargoConfigWriter.write(
                        manager.managedDir, r.crates, settings.localFlintCorePath, toEmit,
                    )
                } catch (e: CargoConfigConflict) {
                    notify("Flint: .cargo/config.toml conflict", e.message ?: "", NotificationType.ERROR)
                    return
                }

                if (profile == FlintProfile.FLINT && profile.cargoName !in nativeProfiles) {
                    if (closure.isEmpty()) {
                        notify(
                            "Flint: cannot debug",
                            "The open SteelMC workspace root Cargo.toml does not define " +
                                "[profile.${profile.cargoName}]. Debug requires it. Add e.g.:\n\n" +
                                "[profile.${profile.cargoName}]\ninherits = \"dev\"\ndebug = true",
                            NotificationType.ERROR,
                        )
                        return
                    }
                    when (val ir = CloneProfileInjector.inject(manager.managedDir, toEmit)) {
                        is InjectResult.Conflict -> {
                            notify("Flint: cannot debug", ir.message, NotificationType.ERROR)
                            return
                        }
                        // Injected / NothingToDo / AlreadyDefined: profile is
                        // resolvable on the clone — proceed.
                        else -> {}
                    }
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
        // No `--profile` here: the Rust plugin owns Cargo-profile selection via
        // the execution target picked below. Passing it twice would conflict.
        val args = listOf(
            "--lib", entrypoint,
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
            try {
                val runManager = RunManager.getInstance(project)
                val rc = runManager.createCargoCommandRunConfiguration(
                    cmd, "Flint: ${mode.name.lowercase()} @ $version",
                )
                // The Rust plugin replaces the platform `<default>` execution
                // target with one target per Cargo profile. A transient config
                // launched against the project's active `<default>` target
                // fails ExecutionTargetManager.canRun. Pick the profile target
                // explicitly instead.
                val target = ExecutionTargetManager.getInstance(project)
                    .getTargetsFor(rc.configuration)
                    .firstOrNull { it is RsDefaultProfileExecutionTarget && it.matchesProfile(profile) }
                when {
                    target != null ->
                        ExecutionEnvironmentBuilder.create(executor, rc)
                            .target(target)
                            .buildAndExecute()

                    profile == FlintProfile.FLINT -> {
                        // Profile target not enumerated yet (cold-index race).
                        // The clone manifest already declares [profile.flint]
                        // (ensured above), so pass --profile explicitly on the
                        // default target. No double --profile: no profile
                        // target is selected. --profile precedes the `--`
                        // separator so it binds to `cargo test`.
                        val cmd2 = CargoCommandLine(
                            command = "test",
                            workingDirectory = manager.managedDir,
                            additionalArguments = listOf("--profile", profile.cargoName) + args,
                            environmentVariables = env,
                        )
                        val rc2 = runManager.createCargoCommandRunConfiguration(
                            cmd2, "Flint: ${mode.name.lowercase()} @ $version (${profile.cargoName})",
                        )
                        ExecutionEnvironmentBuilder.create(executor, rc2).buildAndExecute()
                    }

                    else -> notify(
                        "Flint: cannot run",
                        "Cargo profile '${profile.cargoName}' target is not available " +
                            "on the flint-steel clone. Is the clone indexed?",
                        NotificationType.ERROR,
                    )
                }
            } catch (e: Exception) {
                thisLogger().warn("Flint delegate launch failed", e)
                notify("Flint run failed", e.message ?: e.toString(), NotificationType.ERROR)
            }
        }
    }

    /** True if this Rust profile target's Cargo profile matches [profile]. */
    private fun RsDefaultProfileExecutionTarget.matchesProfile(profile: FlintProfile): Boolean =
        when (val bp = buildProfile) {
            is CargoBuildProfile.CustomCargoBuildProfile -> bp.name == profile.cargoName
            is CargoBuildProfile.DefaultCargoBuildProfile -> bp.parameter == profile.cargoName
            else -> false
        }

    /** Precedence: run-config override > menu defaults > .env (vars left unset). */
    private fun buildEnv(settings: FlintSettings.State): Map<String, String> {
        val env = FlintSettings.getInstance(project).toEnv().toMutableMap()
        if (mode == FlintMode.ALL) {
            // All mode ignores the filter entirely.
            env.remove("FLINT_TEST"); env.remove("FLINT_TAGS"); env.remove("FLINT_PATTERN")
        } else {
            if (overrideTest.isNotBlank()) env["FLINT_TEST"] = overrideTest
            if (overrideTags.isNotBlank()) {
                env["FLINT_TAGS"] = overrideTags
            } else {
                val selectedTagString = selectedTags.filter { it.isNotBlank() }.joinToString(",")
                if (selectedTagString.isNotBlank()) env["FLINT_TAGS"] = selectedTagString
            }
            if (overridePattern.isNotBlank()) env["FLINT_PATTERN"] = overridePattern
        }
        // PLAN-VIZ §4: blank flintVizUrl → fall back to a Flint Viz config's
        // derived URL (defaults 127.0.0.1:7878). Non-blank setting already won
        // via toEnv() above, so only fill when still unset.
        if (!env.containsKey("FLINT_VIZ_URL")) {
            env["FLINT_VIZ_URL"] = derivedVizUrl()
        }
        env.putAll(extraEnv.filterValues { it.isNotBlank() })
        return env
    }

    /** Last-used (else first) Flint Viz config's URL, or the viz defaults. */
    private fun derivedVizUrl(): String {
        val rm = RunManager.getInstance(project)
        val selected = rm.selectedConfiguration?.configuration as? FlintVizRunConfiguration
        val viz = selected
            ?: rm.allConfigurationsList.filterIsInstance<FlintVizRunConfiguration>().firstOrNull()
        return viz?.derivedVizUrl()
            ?: "http://${FlintVizRunConfiguration.DEFAULT_HOST}:${FlintVizRunConfiguration.DEFAULT_PORT}"
    }

    internal fun notify(title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Flint")
            .createNotification(title, content, type)
            .notify(project)
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "mode", mode.name)
        JDOMExternalizerUtil.writeField(element, "version", version)
        JDOMExternalizerUtil.writeField(element, "overrideTags", overrideTags)
        JDOMExternalizerUtil.writeField(element, "selectedTags", selectedTags.joinToString(","))
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
        // "profile" field retired (now executor-derived); old persisted value
        // is intentionally ignored and dropped on next save.
        version = JDOMExternalizerUtil.readField(element, "version", "latest")
        overrideTags = JDOMExternalizerUtil.readField(element, "overrideTags", "")
        selectedTags = JDOMExternalizerUtil.readField(element, "selectedTags", "")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toMutableList()
        overrideTest = JDOMExternalizerUtil.readField(element, "overrideTest", "")
        overridePattern = JDOMExternalizerUtil.readField(element, "overridePattern", "")
        extraEnv = JDOMExternalizerUtil.readField(element, "extraEnv", "")
            .split(";").filter { it.contains("=") }
            .associate { it.substringBefore("=") to it.substringAfter("=") }
            .toMutableMap()
            .let { LinkedHashMap(it) }
    }
}
