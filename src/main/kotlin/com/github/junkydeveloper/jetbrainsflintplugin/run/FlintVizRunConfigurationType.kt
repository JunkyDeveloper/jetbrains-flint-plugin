package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import javax.swing.Icon

/** Run configuration type "Flint Viz" — serves the flint-viz HTTP UI. Run only. */
class FlintVizRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Flint Viz",
    "Serve the flint-viz visualizer (source + 3D world + timeline)",
    NotNullLazyValue.createValue<Icon> { com.github.junkydeveloper.jetbrainsflintplugin.FlintIcons.FLINT },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                FlintVizRunConfiguration(project, this, "Flint Viz")
        })
    }

    companion object {
        const val ID = "FlintVizRunConfiguration"
    }
}
