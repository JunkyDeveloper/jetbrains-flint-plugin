package com.github.junkydeveloper.jetbrainsflintplugin.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

sealed interface InjectResult {
    /** Block appended to the clone manifest. */
    object Injected : InjectResult
    /** No blocks supplied (e.g. profile already native to the clone). */
    object NothingToDo : InjectResult
    /** Clone manifest already defines [profile]; left untouched, use as-is. */
    data class AlreadyDefined(val profile: String) : InjectResult
    /** Manifest missing/unreadable. */
    data class Conflict(val message: String) : InjectResult
}

/**
 * Appends `[profile.*]` tables (lifted from the SteelMC workspace root) to the
 * flint-steel clone's root Cargo.toml so the Rust plugin enumerates them as
 * execution targets and `cargo test --profile <name>` resolves.
 *
 * Idempotent by construction: [FlintSteelManager.checkout] hard-resets the
 * tracked manifest every run, so each run starts pristine and re-applies. The
 * marker fence is stripped defensively before re-appending. A second
 * `[profile.<name>]` is a fatal cargo error, so an existing native definition
 * short-circuits to [InjectResult.AlreadyDefined] without writing.
 */
object CloneProfileInjector {

    const val BEGIN = "# >>> flint plugin: profile injection >>>"
    const val END = "# <<< flint plugin: profile injection <<<"

    private val PROFILE_HEADER = Regex("""^\s*\[profile\.([A-Za-z0-9_-]+)(\..+)?]\s*$""")

    /** Top-level profile names natively declared in the clone's root manifest. */
    fun existingProfiles(cloneDir: Path): Set<String> {
        val manifest = cloneDir.resolve("Cargo.toml")
        if (!Files.isRegularFile(manifest)) return emptySet()
        return stripRegion(manifest.readText()).lineSequence()
            .mapNotNull { PROFILE_HEADER.find(it)?.groupValues?.get(1) }
            .toSet()
    }

    fun inject(cloneDir: Path, blocks: Map<String, String>): InjectResult {
        val manifest = cloneDir.resolve("Cargo.toml")
        if (!Files.isRegularFile(manifest)) {
            return InjectResult.Conflict("No Cargo.toml in the flint-steel clone: $manifest")
        }
        if (blocks.isEmpty()) return InjectResult.NothingToDo

        val original = manifest.readText()
        val stripped = stripRegion(original)

        val existing = stripped.lineSequence()
            .mapNotNull { PROFILE_HEADER.find(it)?.groupValues?.get(1) }
            .toSet()
        blocks.keys.firstOrNull { it in existing }?.let {
            return InjectResult.AlreadyDefined(it)
        }

        val sb = StringBuilder(stripped.trimEnd())
        sb.append("\n\n").append(BEGIN)
        for ((_, block) in blocks) sb.append('\n').append(block.trim())
        sb.append('\n').append(END).append('\n')
        Files.writeString(manifest, sb.toString())
        return InjectResult.Injected
    }

    /** Drop a previously injected `BEGIN..END` fence (inclusive), if present. */
    private fun stripRegion(text: String): String {
        val start = text.indexOf(BEGIN)
        if (start < 0) return text
        val endMarker = text.indexOf(END, start)
        if (endMarker < 0) return text.substring(0, start)
        val after = text.indexOf('\n', endMarker)
        return text.substring(0, start) + if (after < 0) "" else text.substring(after + 1)
    }
}
