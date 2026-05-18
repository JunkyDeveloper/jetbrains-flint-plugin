package com.github.junkydeveloper.jetbrainsflintplugin.settings

import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintCommandException
import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintIndexLocator
import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintIndexReader
import com.github.junkydeveloper.jetbrainsflintplugin.services.FlintSteelManager
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import java.nio.file.Path

/**
 * Settings > Tools > Flint — project defaults: flint-steel/flint-core env
 * vars, managed-clone repo URL, optional local flint-core, tag multiselect,
 * and maintenance actions.
 */
class FlintSettingsConfigurable(private val project: Project) :
    BoundConfigurable("Flint") {

    private val settings = FlintSettings.getInstance(project)
    private val state = settings.state
    private val manager = FlintSteelManager.getInstance(project)

    private val fileDescriptor =
        FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Select Flint Index File")
    private val testFolderDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Flint Test Directory")
    private val coreFolderDescriptor =
        FileChooserDescriptorFactory.createSingleFolderDescriptor()
            .withTitle("Select Local flint-core Crate")

    private val tagList = CheckBoxList<String>()

    private fun availableTags(): List<String> =
        (FlintIndexReader.readTags(manager.indexPath()) + state.selectedTags)
            .filter { it.isNotBlank() }.distinct().sorted()

    private fun populateTagList() {
        tagList.clear()
        for (tag in availableTags()) {
            tagList.addItem(tag, tag, tag in state.selectedTags)
        }
    }

    private fun checkedTags(): List<String> =
        (0 until tagList.itemsCount).mapNotNull { i ->
            tagList.getItemAt(i)?.takeIf { tagList.isItemSelected(i) }
        }

    override fun createPanel(): DialogPanel {
        populateTagList()
        return panel {
            group("flint-core") {
                row("Index file (INDEX_NAME):") {
                    textFieldWithBrowseButton(fileDescriptor)
                        .bindText(state::indexName)
                        .comment("Path to the flint index JSON (default: .cache/index.json)")
                        .align(AlignX.FILL)
                }
                row("Default tag (DEFAULT_TAG):") {
                    textField().bindText(state::defaultTag).align(AlignX.FILL)
                }
                row("Test path (TEST_PATH):") {
                    textFieldWithBrowseButton(testFolderDescriptor)
                        .bindText(state::testPath)
                        .comment("Directory scanned for flint tests (default: ./test)")
                        .align(AlignX.FILL)
                }
                row("Local flint-core path (optional):") {
                    textFieldWithBrowseButton(coreFolderDescriptor)
                        .bindText(state::localFlintCorePath)
                        .comment("If set, adds a [patch] flint-core = { path = … } block")
                        .align(AlignX.FILL)
                }
            }
            group("flint-steel") {
                row("Repo URL:") {
                    textField().bindText(state::flintSteelRepoUrl).align(AlignX.FILL)
                }
                row("Test name (FLINT_TEST):") {
                    textField().bindText(state::flintTest).align(AlignX.FILL)
                }
                row("Tags (FLINT_TAGS, overrides selection):") {
                    textField().bindText(state::flintTags).align(AlignX.FILL)
                }
                row("Pattern (FLINT_PATTERN):") {
                    textField().bindText(state::flintPattern).align(AlignX.FILL)
                }
                row("Visualizer URL (FLINT_VIZ_URL):") {
                    textField().bindText(state::flintVizUrl).align(AlignX.FILL)
                }
            }
            group("Tag selection") {
                row {
                    cell(JBScrollPane(tagList)).align(AlignX.FILL)
                        .comment("From the index; used as FLINT_TAGS when the field above is blank")
                }
            }
            group("Maintenance") {
                row {
                    button("Refresh tags") { refreshTags() }
                    button("Cargo Clean") { cargoClean() }
                    button("Open managed dir") {
                        RevealFileAction.openDirectory(manager.managedDir.toFile())
                    }
                }
            }
        }
    }

    private fun refreshTags() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val error = runCatching {
                val base = project.basePath
                    ?: throw FlintCommandException(
                        "Open a project first — flint-index needs a project directory.",
                    )
                FlintIndexLocator.getInstance(project).buildIndex(Path.of(base))
            }.exceptionOrNull()
            ApplicationManager.getApplication().invokeLater({
                if (error != null) {
                    Messages.showErrorDialog(project, error.message ?: "$error", "Flint: Refresh Failed")
                }
                populateTagList()
                if (error == null && tagList.itemsCount == 0) {
                    Messages.showInfoMessage(
                        project,
                        "flint-index produced no tags. Check TEST_PATH (${state.testPath}) under the project base.",
                        "Flint: No Tags",
                    )
                }
            }, ModalityState.any())
        }
    }

    private fun cargoClean() {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { manager.cargoClean() }
            ApplicationManager.getApplication().invokeLater({
                Messages.showInfoMessage(project, "Removed ${manager.managedDir}/target", "Flint: Cargo Clean")
            }, ModalityState.any())
        }
    }

    override fun isModified(): Boolean =
        super.isModified() || checkedTags() != state.selectedTags

    override fun apply() {
        super.apply()
        state.selectedTags = checkedTags().toMutableList()
    }

    override fun reset() {
        super.reset()
        populateTagList()
    }
}
