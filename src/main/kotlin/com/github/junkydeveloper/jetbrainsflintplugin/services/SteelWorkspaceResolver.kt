package com.github.junkydeveloper.jetbrainsflintplugin.services

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/** The four SteelMC workspace crates flint-steel patches in. */
private val STEEL_CRATES = listOf("steel-core", "steel-protocol", "steel-registry", "steel-utils")

sealed interface SteelResolution {
    data class Resolved(
        val crates: Map<String, Path>,
        /**
         * `[profile.*]` tables lifted verbatim from the SteelMC workspace-root
         * Cargo.toml, keyed by top-level profile name. Empty when none.
         */
        val profileBlocks: Map<String, String>,
    ) : SteelResolution
    data class Failure(val message: String) : SteelResolution
}

/**
 * Resolves the four `steel-*` crate directories from the open project's Cargo
 * workspace so the generated `.cargo/config.toml` patch points at the user's
 * current server code. Hard-fails (typed Failure) if any crate is missing.
 *
 * Minimal TOML scan: enough for `[workspace] members = [...]` (with globs) and
 * each member's `[package] name`.
 */
object SteelWorkspaceResolver {

    fun resolve(projectBasePath: String?): SteelResolution {
        val base = projectBasePath?.let { Path.of(it) }
            ?: return SteelResolution.Failure("No project base path; open the SteelMC workspace.")
        val rootManifest = base.resolve("Cargo.toml")
        if (!Files.isRegularFile(rootManifest)) {
            return SteelResolution.Failure("No Cargo.toml at project root: $base")
        }

        val rootText = rootManifest.readText()
        val members = parseMembers(rootText)
        if (members.isEmpty()) {
            return SteelResolution.Failure(
                "No [workspace].members in $rootManifest — is this the SteelMC workspace root?",
            )
        }

        val byName = mutableMapOf<String, Path>()
        for (memberDir in expandMembers(base, members)) {
            val manifest = memberDir.resolve("Cargo.toml")
            if (!Files.isRegularFile(manifest)) continue
            val name = parsePackageName(manifest.readText()) ?: continue
            if (name in STEEL_CRATES) byName[name] = memberDir
        }

        val missing = STEEL_CRATES.filter { it !in byName }
        if (missing.isNotEmpty()) {
            return SteelResolution.Failure(
                "Open workspace is missing required steel crate(s): ${missing.joinToString(", ")}.\n" +
                    "Open the full SteelMC workspace so flint-steel can patch them.",
            )
        }
        return SteelResolution.Resolved(byName, parseProfileBlocks(rootText))
    }

    private val BUILTIN_PROFILES = setOf("dev", "release", "test", "bench")
    private val PROFILE_HEADER = Regex("""^\s*\[profile\.([A-Za-z0-9_-]+)(\..+)?]\s*$""")
    private val TABLE_HEADER = Regex("""^\s*\[""")
    private val INHERITS = Regex("""^\s*inherits\s*=\s*["']([^"']+)["']""")

    /**
     * Lift every `[profile.<name>]` table — plus its nested
     * `[profile.<name>.<sub>]` sub-tables — out of a workspace-root Cargo.toml,
     * keyed by top-level profile name. Verbatim header lines; body lines have
     * inline comments stripped (consistent with [parseMembers]).
     */
    private fun parseProfileBlocks(toml: String): Map<String, String> {
        val blocks = linkedMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (raw in toml.lineSequence()) {
            val header = PROFILE_HEADER.find(raw)
            if (header != null) {
                current = blocks.getOrPut(header.groupValues[1]) { StringBuilder() }
                if (current.isNotEmpty()) current.append('\n')
                current.append(raw.trimEnd())
                continue
            }
            if (TABLE_HEADER.containsMatchIn(raw)) { current = null; continue }
            val sb = current ?: continue
            val body = raw.substringBefore('#').trimEnd()
            if (body.isNotBlank()) sb.append('\n').append(body)
        }
        return blocks.mapValues { it.value.toString() }
    }

    /**
     * Blocks needed to define [profile] in another manifest: the profile
     * itself plus, transitively, any custom `inherits` base that is not a
     * Cargo built-in. Empty if [profile] is absent. Cycle-guarded.
     */
    fun profileClosure(blocks: Map<String, String>, profile: String): Map<String, String> {
        if (profile !in blocks) return emptyMap()
        val out = linkedMapOf<String, String>()
        val queue = ArrayDeque(listOf(profile))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name in out) continue
            val block = blocks[name] ?: continue
            out[name] = block
            val base = block.lineSequence()
                .firstNotNullOfOrNull { INHERITS.find(it)?.groupValues?.get(1) }
            if (base != null && base !in BUILTIN_PROFILES && base in blocks) queue.addLast(base)
        }
        return out
    }

    /** Members of the `members = [ ... ]` array under `[workspace]` (or top-level). */
    private fun parseMembers(toml: String): List<String> {
        val noComments = toml.lineSequence()
            .joinToString("\n") { it.substringBefore('#') }
        val arrayStart = Regex("""members\s*=\s*\[""").find(noComments) ?: return emptyList()
        val rest = noComments.substring(arrayStart.range.last + 1)
        val body = rest.substringBefore(']')
        return Regex("""["']([^"']+)["']""").findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun parsePackageName(toml: String): String? {
        var inPackage = false
        for (raw in toml.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) {
                inPackage = line == "[package]"
                continue
            }
            if (inPackage) {
                val m = Regex("""^name\s*=\s*["']([^"']+)["']""").find(line)
                if (m != null) return m.groupValues[1]
            }
        }
        return null
    }

    /** Expand glob members such as `crates` slash star to concrete directories. */
    private fun expandMembers(base: Path, members: List<String>): List<Path> = buildList {
        for (entry in members) {
            if (entry.endsWith("/*") || entry == "*") {
                val parent = if (entry == "*") base else base.resolve(entry.removeSuffix("/*"))
                if (parent.isDirectory()) {
                    parent.listDirectoryEntries()
                        .filter { it.isDirectory() && Files.isRegularFile(it.resolve("Cargo.toml")) }
                        .forEach { add(it) }
                }
            } else {
                add(base.resolve(entry))
            }
        }
    }
}
