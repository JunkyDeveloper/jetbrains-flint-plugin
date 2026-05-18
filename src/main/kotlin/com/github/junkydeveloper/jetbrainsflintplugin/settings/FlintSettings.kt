package com.github.junkydeveloper.jetbrainsflintplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

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
        // flint-core (src/utils.rs)
        var indexName: String = ".cache/index.json"
        var defaultTag: String = "default"
        var testPath: String = "./test"

        // flint-steel (src/adapter.rs)
        var flintTest: String = ""
        var flintTags: String = ""
        var flintPattern: String = ""
        var flintVizUrl: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    /** Non-blank values as an ENV_VAR -> value map for process injection. */
    fun toEnv(): Map<String, String> = buildMap {
        fun put(key: String, value: String) {
            if (value.isNotBlank()) put(key, value)
        }
        put("INDEX_NAME", state.indexName)
        put("DEFAULT_TAG", state.defaultTag)
        put("TEST_PATH", state.testPath)
        put("FLINT_TEST", state.flintTest)
        put("FLINT_TAGS", state.flintTags)
        put("FLINT_PATTERN", state.flintPattern)
        put("FLINT_VIZ_URL", state.flintVizUrl)
    }

    companion object {
        fun getInstance(project: Project): FlintSettings = project.service()
    }
}
