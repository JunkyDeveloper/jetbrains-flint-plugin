package com.github.junkydeveloper.jetbrainsflintplugin.services

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the tag universe from the flint-core index JSON
 * (`Index { hash, index: BTreeMap<String, Vec<String>> }`). The tags are the
 * keys of `index`. Missing / unparseable file -> empty list (caller hints the
 * user to Refresh).
 */
object FlintIndexReader {

    private val mapper = ObjectMapper()

    fun readTags(indexPath: Path): List<String> {
        if (!Files.isRegularFile(indexPath)) return emptyList()
        return try {
            val root = mapper.readTree(Files.newInputStream(indexPath))
            val index = root.get("index") ?: return emptyList()
            index.fieldNames().asSequence().toSortedSet().toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
