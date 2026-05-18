package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

class FlintVizRunConfigurationEditor(@Suppress("unused") private val project: Project) :
    SettingsEditor<FlintVizRunConfiguration>() {

    private val modeCombo = ComboBox(DefaultComboBoxModel(VizMode.values()))
    private val hostField = JBTextField()
    private val portField = JBTextField()
    private val openCheck = JBCheckBox("Open browser on start")

    override fun createEditor(): JComponent = panel {
        row("Mode:") { cell(modeCombo) }
        row("Host:") { cell(hostField).align(AlignX.FILL) }
        row("Port:") { cell(portField) }
        row { cell(openCheck) }
        row {
            comment(
                "Test-folder mode serves the open SteelMC project's TEST_PATH " +
                    "(editable). Readonly serves failures posted to FLINT_VIZ_URL.",
            )
        }
    }

    override fun resetEditorFrom(s: FlintVizRunConfiguration) {
        modeCombo.selectedItem = s.mode
        hostField.text = s.host
        portField.text = s.port.toString()
        openCheck.isSelected = s.openBrowser
    }

    override fun applyEditorTo(s: FlintVizRunConfiguration) {
        s.mode = modeCombo.selectedItem as? VizMode ?: VizMode.TEST_FOLDER
        s.host = hostField.text.trim().ifBlank { FlintVizRunConfiguration.DEFAULT_HOST }
        s.port = portField.text.trim().toIntOrNull() ?: FlintVizRunConfiguration.DEFAULT_PORT
        s.openBrowser = openCheck.isSelected
    }
}
