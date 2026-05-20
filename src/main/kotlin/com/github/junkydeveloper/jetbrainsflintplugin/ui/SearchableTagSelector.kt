package com.github.junkydeveloper.jetbrainsflintplugin.ui

import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SearchableTagSelector : JPanel(BorderLayout(0, JBUI.scale(4))) {

    private val searchField = JBTextField().apply {
        emptyText.text = "Search tags"
    }
    private val tagList = CheckBoxList<String>()
    private val selectedTags = linkedSetOf<String>()

    private var allTags: List<String> = emptyList()

    init {
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refilter()
            override fun removeUpdate(e: DocumentEvent) = refilter()
            override fun changedUpdate(e: DocumentEvent) = refilter()
        })
        add(searchField, BorderLayout.NORTH)
        add(JBScrollPane(tagList), BorderLayout.CENTER)
    }

    fun setTags(tags: Collection<String>, selected: Collection<String>) {
        selectedTags.clear()
        selectedTags.addAll(selected.filter { it.isNotBlank() })
        allTags = (tags + selectedTags)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
        rebuildList()
    }

    fun selectedTags(): List<String> {
        syncVisibleSelection()
        return allTags.filter { it in selectedTags }
    }

    val itemsCount: Int
        get() = allTags.size

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        searchField.isEnabled = enabled
        tagList.isEnabled = enabled
    }

    private fun refilter() {
        syncVisibleSelection()
        rebuildList()
    }

    private fun syncVisibleSelection() {
        for (i in 0 until tagList.itemsCount) {
            val tag = tagList.getItemAt(i) ?: continue
            if (tagList.isItemSelected(i)) {
                selectedTags.add(tag)
            } else {
                selectedTags.remove(tag)
            }
        }
    }

    private fun rebuildList() {
        val query = searchField.text.trim()
        val visibleTags = if (query.isEmpty()) {
            allTags
        } else {
            allTags.filter { it.contains(query, ignoreCase = true) }
        }

        tagList.clear()
        for (tag in visibleTags) {
            tagList.addItem(tag, tag, tag in selectedTags)
        }
        tagList.revalidate()
        tagList.repaint()
    }
}
