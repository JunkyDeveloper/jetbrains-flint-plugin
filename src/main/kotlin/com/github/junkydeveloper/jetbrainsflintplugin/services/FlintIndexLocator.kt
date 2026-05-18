package com.github.junkydeveloper.jetbrainsflintplugin.services

import com.github.junkydeveloper.jetbrainsflintplugin.settings.FlintSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** flint-index could not be located/run on this host. Message is user-facing. */
class IndexerUnavailable(message: String) : RuntimeException(message)

/**
 * Owns the bundled `flint-index` binary: extracts the vendored glibc x86-64
 * ELF from the plugin jar and runs it to (re)build the flint-core test index.
 * No PATH lookup — the bundled copy is always used. Unsupported host →
 * [IndexerUnavailable].
 */
@Service(Service.Level.PROJECT)
class FlintIndexLocator(private val project: Project) {

    private val settings get() = FlintSettings.getInstance(project)

    /** Extracted bundled binary path; throws on unsupported host. */
    fun resolve(): Path {
        if (!(SystemInfo.isLinux && IndexerCpuArch.isX86_64())) {
            throw IndexerUnavailable(
                "The bundled flint-index binary only runs on Linux x86-64 " +
                    "(this host: ${SystemInfo.OS_NAME} ${IndexerCpuArch.archName()}).",
            )
        }
        return extractBundled()
    }

    /** Run flint-index with cwd = [workDir], env = login env + settings.toEnv(). */
    fun buildIndex(workDir: Path) {
        val exe = resolve()
        val cl = GeneralCommandLine(exe.toString())
            .withCharset(Charsets.UTF_8)
            .withEnvironment(EnvironmentUtil.getEnvironmentMap() + settings.toEnv())
        cl.workDirectory = workDir.toFile()
        val out = CapturingProcessHandler(cl).runProcess(120_000)
        if (out.isTimeout || out.exitCode != 0) {
            throw FlintCommandException(
                "`flint-index` failed (exit ${out.exitCode})\n${out.stderr.ifBlank { out.stdout }}",
            )
        }
    }

    /** Extract `bin/flint-index` resource to the IDE system path; re-extract on sha drift. */
    private fun extractBundled(): Path {
        val target = managedIndexerDir().resolve("flint-index")
        val resourceBytes = javaClass.getResourceAsStream("/bin/flint-index")?.readBytes()
            ?: throw IndexerUnavailable("Bundled flint-index binary missing from plugin jar.")

        val needsWrite = !Files.exists(target) || sha256(Files.readAllBytes(target)) != sha256(resourceBytes)
        if (needsWrite) {
            Files.createDirectories(target.parent)
            Files.write(target, resourceBytes)
            runCatching {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
            }.onFailure { thisLogger().warn("chmod flint-index failed (continuing)", it) }
        }
        return target
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        fun getInstance(project: Project): FlintIndexLocator = project.service()

        /** `<system>/flint-plugin/flint-index` — shared across projects. */
        fun managedIndexerDir(): Path =
            Path.of(PathManager.getSystemPath(), "flint-plugin", "flint-index")
    }
}

/** Minimal arch check (avoids depending on a specific platform-util API). */
private object IndexerCpuArch {
    private val arch = System.getProperty("os.arch").orEmpty().lowercase()
    fun isX86_64(): Boolean = arch == "amd64" || arch == "x86_64"
    fun archName(): String = arch.ifBlank { "unknown" }
}
