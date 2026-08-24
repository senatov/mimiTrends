package org.senatov.mimitrends

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

internal object WorkspaceEmptyState {
    fun create(title: String, detail: String, actionLabel: String? = null, onAction: () -> Unit = {}): VBox {
        val mark = StackPane(Label("—")).apply { styleClass += "workspace-empty-mark" }
        val heading = Label(title).apply { styleClass += "workspace-empty-title" }
        val description = Label(detail).apply {
            styleClass += "workspace-empty-detail"
            isWrapText = true
            maxWidth = 360.0
        }
        val content = mutableListOf<javafx.scene.Node>(mark, heading, description)
        actionLabel?.let { caption ->
            content += Button(caption).apply {
                styleClass += "empty-state-action"
                accessibleText = caption
                setOnAction { onAction() }
            }
        }
        return VBox(7.0).apply {
            children += content
            alignment = Pos.CENTER
            styleClass += "workspace-empty-state"
            isMouseTransparent = actionLabel == null
        }
    }
}
