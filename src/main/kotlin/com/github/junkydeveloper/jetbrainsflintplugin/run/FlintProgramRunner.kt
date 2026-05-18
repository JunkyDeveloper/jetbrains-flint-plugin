package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Owns execution of [FlintRunConfiguration] for both the Run and Debug
 * executors. Without this, no registered runner reports `canRun(Debug, ...)`
 * for the Flint config so the Debug button is dead; the generic platform
 * runner only covers Run.
 *
 * Prep (clone checkout, .cargo/config.toml, attach Cargo project) is long, so
 * it runs off the EDT. The actual launch delegates to a transient Cargo
 * `test` config via [FlintRunConfiguration.launchFlint], executed with the
 * same executor — Run → Cargo run runner, Debug → Rust plugin native LLDB.
 */
class FlintProgramRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = "FlintProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        profile is FlintRunConfiguration &&
            (executorId == DefaultRunExecutor.EXECUTOR_ID ||
                executorId == DefaultDebugExecutor.EXECUTOR_ID)

    override fun execute(environment: ExecutionEnvironment) {
        val config = environment.runProfile as? FlintRunConfiguration ?: return
        val executor = environment.executor
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                config.launchFlint(executor)
            } catch (e: Exception) {
                thisLogger().warn("Flint run failed", e)
                config.notify("Flint run failed", e.message ?: e.toString(), NotificationType.ERROR)
            }
        }
    }
}
