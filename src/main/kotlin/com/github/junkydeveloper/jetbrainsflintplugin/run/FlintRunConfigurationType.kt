package com.github.junkydeveloper.jetbrainsflintplugin.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import javax.swing.Icon

/** Run/Debug configuration type "Flint" — drives flint-steel against SteelMC. */
class FlintRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Flint",
    "Run flint-steel benchmarks against the open SteelMC workspace",
    NotNullLazyValue.createValue<Icon> { com.intellij.icons.AllIcons.Actions.Execute },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId(): String = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                FlintRunConfiguration(project, this, "Flint")
        })
    }

    companion object {
        const val ID = "FlintRunConfiguration"
    }
}
