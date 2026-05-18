package com.github.junkydeveloper.jetbrainsflintplugin.services

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** flint-viz could not be located/run on this host. Message is user-facing. */
class VizUnavailable(message: String) : RuntimeException(message)

/** Where the flint-viz executable lives. */
sealed class VizExecutable {
    abstract val path: Path

    /** Found on $PATH (deb / `cargo install`). */
    data class PathBin(override val path: Path) : VizExecutable()

    /** Extracted from the bundled jar resource. */
    data class Bundled(override val path: Path) : VizExecutable()
}

/**
 * Resolves the flint-viz executable: prefer a copy on $PATH, else extract the
 * bundled glibc x86-64 binary from the plugin jar. Unsupported host with
 * nothing on $PATH → [VizUnavailable].
 */
@Service(Service.Level.PROJECT)
class FlintVizLocator(@Suppress("unused") private val project: Project) {

    fun resolve(): VizExecutable {
        PathEnvironmentVariableUtil.findInPath("flint-viz")?.let {
            return VizExecutable.PathBin(it.toPath())
        }

        if (!(SystemInfo.isLinux && CpuArch.isX86_64())) {
            throw VizUnavailable(
                "flint-viz not found on PATH and the bundled binary only runs on " +
                    "Linux x86-64 (this host: ${SystemInfo.OS_NAME} ${CpuArch.archName()}). " +
                    "Install it (`cargo install --git https://github.com/FlintTestMC/FlintViz flint-viz`) " +
                    "or put a `flint-viz` executable on your PATH.",
            )
        }

        return VizExecutable.Bundled(extractBundled())
    }

    /** Extract `bin/flint-viz` resource to the IDE system path; re-extract on sha drift. */
    private fun extractBundled(): Path {
        val target = managedVizDir().resolve("flint-viz")
        val resourceBytes = javaClass.getResourceAsStream("/bin/flint-viz")?.readBytes()
            ?: throw VizUnavailable("Bundled flint-viz binary missing from plugin jar.")

        val needsWrite = !Files.exists(target) || sha256(Files.readAllBytes(target)) != sha256(resourceBytes)
        if (needsWrite) {
            Files.createDirectories(target.parent)
            Files.write(target, resourceBytes)
            runCatching {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
            }.onFailure { thisLogger().warn("chmod flint-viz failed (continuing)", it) }
        }
        return target
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        fun getInstance(project: Project): FlintVizLocator = project.service()

        /** `<system>/flint-plugin/flint-viz` — shared across projects. */
        fun managedVizDir(): Path =
            Path.of(PathManager.getSystemPath(), "flint-plugin", "flint-viz")
    }
}

/** Minimal arch check (avoids depending on a specific platform-util API). */
private object CpuArch {
    private val arch = System.getProperty("os.arch").orEmpty().lowercase()
    fun isX86_64(): Boolean = arch == "amd64" || arch == "x86_64"
    fun archName(): String = arch.ifBlank { "unknown" }
}
