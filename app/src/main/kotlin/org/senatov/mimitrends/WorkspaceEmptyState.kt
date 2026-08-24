package org.senatov.mimitrends

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

internal object WorkspaceEmptyState {
    fun create(title: String, detail: String): VBox {
        val mark = StackPane(Label("—")).apply { styleClass += "workspace-empty-mark" }
        val heading = Label(title).apply { styleClass += "workspace-empty-title" }
        val description = Label(detail).apply {
            styleClass += "workspace-empty-detail"
            isWrapText = true
            maxWidth = 360.0
        }
        return VBox(7.0, mark, heading, description).apply {
            alignment = Pos.CENTER
            styleClass += "workspace-empty-state"
            isMouseTransparent = true
        }
    }
}
