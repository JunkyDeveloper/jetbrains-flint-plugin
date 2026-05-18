package com.github.junkydeveloper.jetbrainsflintplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.nio.file.Path

/**
 * Persisted flint-steel / flint-core environment variables, applied to the
 * process environment when flint is invoked.
 *
 * Defaults mirror flint-core/src/utils.rs and flint-steel/.env.example.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "FlintSettings",
    storages = [Storage("flint.xml")],
)
class FlintSettings : PersistentStateComponent<FlintSettings.State> {

    class State {
        // flint-core (src/utils.rs) — default points inside the managed clone
        var indexName: String = defaultIndexPath()
        var defaultTag: String = "default"
        var testPath: String = "./test"

        // flint-steel (src/adapter.rs)
        var flintTest: String = ""
        var flintTags: String = ""
        var flintPattern: String = ""
        var flintVizUrl: String = ""

        // Managed clone / topology
        var flintSteelRepoUrl: String = "git@github.com:FlintTestMC/flint-steel.git"
        var localFlintCorePath: String = ""

        /** Tags selected in the menu; joined into FLINT_TAGS when flintTags is blank. */
        var selectedTags: MutableList<String> = mutableListOf()
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    /** Non-blank values as an ENV_VAR -> value map for process injection. */
    fun toEnv(): Map<String, String> = buildMap {
        fun add(key: String, value: String) {
            if (value.isNotBlank()) put(key, value)
        }
        add("INDEX_NAME", state.indexName)
        add("DEFAULT_TAG", state.defaultTag)
        add("TEST_PATH", state.testPath)
        add("FLINT_TEST", state.flintTest)
        val tags = state.flintTags.ifBlank {
            state.selectedTags.filter { it.isNotBlank() }.joinToString(",")
        }
        add("FLINT_TAGS", tags)
        add("FLINT_PATTERN", state.flintPattern)
        add("FLINT_VIZ_URL", state.flintVizUrl)
    }

    companion object {
        fun getInstance(project: Project): FlintSettings = project.service()

        /** Managed flint-steel clone dir under the IDE system path. */
        fun managedSteelDir(): Path =
            Path.of(PathManager.getSystemPath(), "flint-plugin", "flint-steel")

        /** Default INDEX_NAME: absolute, inside the plugin's managed clone. */
        fun defaultIndexPath(): String =
            managedSteelDir().resolve(".cache").resolve("index.json").toString()
    }
}
