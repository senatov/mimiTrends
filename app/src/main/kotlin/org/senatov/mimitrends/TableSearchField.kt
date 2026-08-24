package org.senatov.mimitrends

import javafx.scene.control.TextField
import javafx.scene.control.Button
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox

internal object TableSearchField {
    fun create(
        prompt: String,
        onChanged: () -> Unit,
        onSubmit: () -> Unit = {},
        onEscape: () -> Unit = {}
    ): TableSearchControl = TableSearchControl(prompt, onChanged, onSubmit, onEscape)
}

internal class TableSearchControl(
    prompt: String,
    onChanged: () -> Unit,
    onSubmit: () -> Unit,
    onEscape: () -> Unit
) : HBox() {
    private val input = TextField()
    private val clear = Button("×")
    val text: String get() = input.text.orEmpty()

    init {
        styleClass += "table-search-control"
        input.apply {
            promptText = prompt
            accessibleText = prompt
            accessibleHelp = "Type to filter, then press Enter to open the first result"
            styleClass += "table-search-field"
            prefWidth = 135.0
            minWidth = 90.0
            textProperty().addListener { _, _, value ->
                clear.isVisible = value.isNotEmpty()
                clear.isManaged = clear.isVisible
                onChanged()
            }
            setOnKeyPressed { event ->
                if (event.code == KeyCode.ESCAPE) {
                    input.clear()
                    onEscape()
                    event.consume()
                }
            }
            setOnAction { onSubmit() }
        }
        clear.apply {
            styleClass += "table-search-clear"
            accessibleText = "Clear search"
            isVisible = false
            isManaged = false
            setOnAction { input.clear(); input.requestFocus() }
        }
        children += listOf(input, clear)
    }

    fun focusField() = input.requestFocus()

    fun clear(): Boolean {
        if (text.isEmpty()) return false
        input.clear()
        return true
    }
}
