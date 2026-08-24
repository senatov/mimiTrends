package org.senatov.mimitrends

import javafx.scene.control.TextField

internal object TableSearchField {
    fun create(prompt: String, onChanged: () -> Unit): TextField = TextField().apply {
        promptText = prompt
        accessibleText = prompt
        styleClass += "table-search-field"
        prefWidth = 155.0
        minWidth = 105.0
        textProperty().addListener { _, _, _ -> onChanged() }
    }
}
