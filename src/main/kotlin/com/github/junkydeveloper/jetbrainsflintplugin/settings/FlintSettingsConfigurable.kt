package com.github.junkydeveloper.jetbrainsflintplugin.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings > Tools > Flint — text fields and file/folder pickers for every
 * flint-steel / flint-core environment variable.
 */
class FlintSettingsConfigurable(project: Project) :
    BoundConfigurable("Flint") {

    private val state = FlintSettings.getInstance(project).state

    private val fileDescriptor =
        FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Select Flint Index File")
    private val folderDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Flint Test Directory")

    override fun createPanel(): DialogPanel = panel {
        group("flint-core") {
            row("Index file (INDEX_NAME):") {
                textFieldWithBrowseButton(fileDescriptor)
                    .bindText(state::indexName)
                    .comment("Path to the flint index JSON (default: .cache/index.json)")
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row("Default tag (DEFAULT_TAG):") {
                textField()
                    .bindText(state::defaultTag)
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row("Test path (TEST_PATH):") {
                textFieldWithBrowseButton(folderDescriptor)
                    .bindText(state::testPath)
                    .comment("Directory scanned for flint tests (default: ./test)")
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
        }
        group("flint-steel") {
            row("Test name (FLINT_TEST):") {
                textField()
                    .bindText(state::flintTest)
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row("Tags (FLINT_TAGS):") {
                textField()
                    .bindText(state::flintTags)
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row("Pattern (FLINT_PATTERN):") {
                textField()
                    .bindText(state::flintPattern)
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
            row("Visualizer URL (FLINT_VIZ_URL):") {
                textField()
                    .bindText(state::flintVizUrl)
                    .align(com.intellij.ui.dsl.builder.AlignX.FILL)
            }
        }
    }
}
