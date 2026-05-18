package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintVizLocator
import com.github.junkydeveloper.jetbrainsflintplugin.services.VizUnavailable
import com.github.junkydeveloper.jetbrainsflintplugin.settings.FlintSettings
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMExternalizerUtil
import org.jdom.Element
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

enum class VizMode { TEST_FOLDER, READONLY }

/**
 * Runs flint-viz as a long-lived local HTTP server (run only — no debugger).
 * Resolves the executable via [FlintVizLocator], optionally serving the open
 * SteelMC project's test folder. The console Stop button kills the process.
 */
class FlintVizRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : LocatableConfigurationBase<RunProfileState>(project, factory, name) {

    var mode: VizMode = VizMode.TEST_FOLDER
    var host: String = DEFAULT_HOST
    var port: Int = DEFAULT_PORT
    var openBrowser: Boolean = true

    /** Derived viz URL used as the FLINT_VIZ_URL fallback for flint-steel runs. */
    fun derivedVizUrl(): String = "http://$host:$port"

    override fun getConfigurationEditor() = FlintVizRunConfigurationEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val exe = try {
            FlintVizLocator.getInstance(project).resolve().path
        } catch (e: VizUnavailable) {
            throw ExecutionException(e.message, e)
        }

        val cmd = GeneralCommandLine(exe.toString(), "serve")
        if (mode == VizMode.TEST_FOLDER) {
            cmd.addParameter(resolveTestDir().toString())
        }
        cmd.addParameters("--host", host, "--port", port.toString())
        if (openBrowser) cmd.addParameter("--open")

        preflightPort()

        return object : CommandLineState(environment) {
            override fun startProcess(): ProcessHandler {
                val handler = KillableProcessHandler(cmd)
                ProcessTerminatedListener.attach(handler)
                return handler
            }

            override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
                val console: ConsoleView =
                    TextConsoleBuilderFactory.getInstance().createBuilder(environment.project).console
                val handler = startProcess()
                console.attachToProcess(handler)
                console.print(
                    "flint-viz: ${cmd.commandLineString}\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT,
                )
                return DefaultExecutionResult(console, handler)
            }
        }
    }

    /** `<project base>/<TEST_PATH setting>`, validated as an existing dir. */
    private fun resolveTestDir(): Path {
        val base = project.basePath
            ?: throw ExecutionException("No project base path; open a SteelMC project first.")
        val testPath = FlintSettings.getInstance(project).state.testPath.ifBlank { "./test" }
        val dir = Path.of(base).resolve(testPath).normalize()
        if (!Files.isDirectory(dir)) {
            throw ExecutionException(
                "Test folder not found: $dir (TEST_PATH=\"$testPath\"). " +
                    "Set it under Settings ▸ Tools ▸ Flint, or use Readonly mode.",
            )
        }
        return dir
    }

    /** Abort (never kill) when host:port is already taken. */
    private fun preflightPort() {
        try {
            ServerSocket().use {
                it.reuseAddress = false
                it.bind(InetSocketAddress(host, port))
            }
        } catch (e: Exception) {
            throw ExecutionException(
                "Cannot bind $host:$port — port already in use. " +
                    "Stop the other flint-viz instance or pick a different port. (${e.message})",
            )
        }
    }

    override fun writeExternal(element: Element) {
        super.writeExternal(element)
        JDOMExternalizerUtil.writeField(element, "mode", mode.name)
        JDOMExternalizerUtil.writeField(element, "host", host)
        JDOMExternalizerUtil.writeField(element, "port", port.toString())
        JDOMExternalizerUtil.writeField(element, "openBrowser", openBrowser.toString())
    }

    override fun readExternal(element: Element) {
        super.readExternal(element)
        mode = runCatching { VizMode.valueOf(JDOMExternalizerUtil.readField(element, "mode", "TEST_FOLDER")) }
            .getOrDefault(VizMode.TEST_FOLDER)
        host = JDOMExternalizerUtil.readField(element, "host", DEFAULT_HOST)
        port = JDOMExternalizerUtil.readField(element, "port", DEFAULT_PORT.toString()).toIntOrNull() ?: DEFAULT_PORT
        openBrowser = JDOMExternalizerUtil.readField(element, "openBrowser", "true").toBoolean()
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 7878
    }
}
