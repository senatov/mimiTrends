package org.senatov.mimitrends

import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Separator
import javafx.scene.layout.HBox

internal class WorkspaceActionButtons {
    val refresh = Button()
    val settings = Button()
    val importTrades = Button()
    val about = Button()
    val all: List<Button> = listOf(refresh, settings, importTrades, about)

    fun createToolbar(): HBox = HBox(
        8.0,
        refresh, settings, importTrades,
        Separator(Orientation.VERTICAL).apply { styleClass += "toolbar-action-separator" },
        about
    ).apply {
        alignment = Pos.CENTER_RIGHT
        styleClass += "title-actions"
    }
}