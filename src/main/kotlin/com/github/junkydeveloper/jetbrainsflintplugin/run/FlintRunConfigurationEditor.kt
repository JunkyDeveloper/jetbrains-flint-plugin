package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintSteelManager
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class FlintRunConfigurationEditor(private val project: Project) :
    SettingsEditor<FlintRunConfiguration>() {

    private val modeCombo = ComboBox(DefaultComboBoxModel(FlintMode.values()))
    private val versionCombo = ComboBox<String>(DefaultComboBoxModel(arrayOf("latest"))).apply {
        isEditable = true
    }
    private val tagsField = JBTextField()
    private val testField = JBTextField()
    private val patternField = JBTextField()
    private val envComponent = EnvironmentVariablesComponent()

    private fun updateFilterEnablement() {
        val selected = modeCombo.selectedItem == FlintMode.SELECTED
        tagsField.isEnabled = selected
        testField.isEnabled = selected
        patternField.isEnabled = selected
    }

    init {
        modeCombo.addActionListener { updateFilterEnablement() }
    }

    override fun createEditor(): JComponent {
        val p = panel {
            row("Mode:") { cell(modeCombo) }
            row("Version:") {
                cell(versionCombo).align(AlignX.FILL)
                button("Reload") { loadVersions(interactive = true) }
            }
            group("Filter overrides (blank = inherit menu)") {
                row("FLINT_TAGS:") { cell(tagsField).align(AlignX.FILL) }
                row("FLINT_TEST:") { cell(testField).align(AlignX.FILL) }
                row("FLINT_PATTERN:") { cell(patternField).align(AlignX.FILL) }
            }
            row { cell(envComponent).align(AlignX.FILL) }
        }
        // Populate the dropdown when the editor opens; stay silent on failure
        // (only an explicit Reload reports the error).
        loadVersions(interactive = false)
        return p
    }

    private fun loadVersions(interactive: Boolean) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                FlintSteelManager.getInstance(project).listVersions()
            }
            ApplicationManager.getApplication().invokeLater({
                result.onSuccess { versions ->
                    val current = versionCombo.selectedItem as? String
                    versionCombo.model = DefaultComboBoxModel(versions.toTypedArray())
                    if (current != null) versionCombo.selectedItem = current
                }.onFailure { e ->
                    if (interactive) {
                        Messages.showErrorDialog(
                            project,
                            e.message ?: e.toString(),
                            "Flint: Version Query Failed",
                        )
                    }
                }
            }, ModalityState.any())
        }
    }

    override fun resetEditorFrom(s: FlintRunConfiguration) {
        modeCombo.selectedItem = s.mode
        versionCombo.selectedItem = s.version
        tagsField.text = s.overrideTags
        testField.text = s.overrideTest
        patternField.text = s.overridePattern
        envComponent.envData = EnvironmentVariablesData.create(s.extraEnv, true)
        updateFilterEnablement()
    }

    override fun applyEditorTo(s: FlintRunConfiguration) {
        s.mode = modeCombo.selectedItem as? FlintMode ?: FlintMode.SELECTED
        s.version = (versionCombo.selectedItem as? String)?.trim().orEmpty().ifBlank { "latest" }
        s.overrideTags = tagsField.text.trim()
        s.overrideTest = testField.text.trim()
        s.overridePattern = patternField.text.trim()
        s.extraEnv = LinkedHashMap(envComponent.envData.envs)
    }
}
