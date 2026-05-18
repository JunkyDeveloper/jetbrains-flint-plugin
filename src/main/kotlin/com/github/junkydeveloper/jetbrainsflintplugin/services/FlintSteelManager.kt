package com.github.junkydeveloper.jetbrainsflintplugin.services

import com.github.junkydeveloper.jetbrainsflintplugin.settings.FlintSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.EnvironmentUtil
import java.nio.file.Files
import java.nio.file.Path

/** Thrown when a git/cargo command exits non-zero. Message carries stderr. */
class FlintCommandException(message: String) : RuntimeException(message)

/**
 * Owns a single flint-steel git clone under the IDE system path and drives it
 * via the system `git` / `cargo` (inherits the user's SSH keys & credential
 * helper). Single clone: fetch + checkout per run.
 */
@Service(Service.Level.PROJECT)
class FlintSteelManager(private val project: Project) {

    /** `<system>/flint-plugin/flint-steel` — shared across projects. */
    val managedDir: Path = FlintSettings.managedFlintSteelDir()

    private val settings get() = FlintSettings.getInstance(project).state

    fun isCloned(): Boolean = Files.isDirectory(managedDir.resolve(".git"))

    /** Clone if absent. No-op when already cloned. */
    fun ensureClone() {
        if (isCloned()) return
        // A prior clone may have been interrupted (auth failure, cancel, the
        // 120s timeout), leaving a non-empty dir without .git. `git clone`
        // refuses a non-empty target ("destination path already exists and is
        // not an empty directory"), so the state never self-heals. Drop the
        // stale dir before retrying.
        if (Files.exists(managedDir)) {
            managedDir.toFile().deleteRecursively()
        }
        Files.createDirectories(managedDir.parent)
        run(
            managedDir.parent,
            "git", "clone", settings.flintSteelRepoUrl, managedDir.fileName.toString(),
        )
    }

    fun fetch() = run(managedDir, "git", "fetch", "--tags", "--prune", "--force")

    /** Remote tags (newest-ish order preserved) plus a synthetic `latest`. */
    fun listVersions(): List<String> {
        val out = run(null, "git", "ls-remote", "--tags", settings.flintSteelRepoUrl)
        val tags = out.stdoutLines
            .mapNotNull { line ->
                line.substringAfter("refs/tags/", "")
                    .removeSuffix("^{}")
                    .takeIf { it.isNotBlank() }
            }
            .distinct()
        return listOf("latest") + tags
    }

    /** Force-checkout a tag, or the remote default branch for `latest`. */
    fun checkout(ref: String) {
        ensureClone()
        fetch()
        if (ref == "latest") {
            run(managedDir, "git", "remote", "set-head", "origin", "--auto")
            val head = run(
                managedDir, "git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD",
            ).stdout.trim().substringAfter("origin/", "main")
            run(managedDir, "git", "checkout", "-f", head)
            run(managedDir, "git", "reset", "--hard", "origin/$head")
        } else {
            run(managedDir, "git", "checkout", "-f", "tags/$ref")
        }
    }

    /** `rm -rf <clone>/target`. */
    fun cargoClean() {
        val target = managedDir.resolve("target")
        if (Files.exists(target)) {
            target.toFile().deleteRecursively()
        }
    }

    /** Effective index path: absolute INDEX_NAME as-is, else relative to the clone. */
    fun indexPath(): Path {
        val p = Path.of(settings.indexName)
        return if (p.isAbsolute) p else managedDir.resolve(p)
    }

    private val ProcessOutput.stdoutLines: List<String>
        get() = stdout.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun run(workDir: Path?, vararg cmd: String): ProcessOutput {
        // Merge the user's login-shell env (SSH agent socket, full PATH);
        // an IDE launched from a desktop entry otherwise lacks these and git
        // SSH auth fails.
        val cl = GeneralCommandLine(*cmd)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(EnvironmentUtil.getEnvironmentMap())
        if (workDir != null) cl.workDirectory = workDir.toFile()
        val out = CapturingProcessHandler(cl).runProcess(120_000)
        if (out.isTimeout || out.exitCode != 0) {
            throw FlintCommandException(
                "`${cmd.joinToString(" ")}` failed (exit ${out.exitCode})\n${out.stderr.ifBlank { out.stdout }}",
            )
        }
        return out
    }

    companion object {
        fun getInstance(project: Project): FlintSteelManager = project.service()
    }
}
