package org.senatov.mimitrends.ui

import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Separator
import javafx.scene.layout.HBox

internal class WorkspaceActionButtons {
    val refresh = Button()
    val universe = Button("Pool —").apply { styleClass += "toolbar-text-button" }
    val settings = Button()
    val importTrades = Button()
    val importStatus = Label("Trades: not imported this session").apply { styleClass += "import-status" }
    val about = Button()
    val all: List<Button> = listOf(refresh, universe, settings, importTrades, about)

    fun createToolbar(): HBox = HBox(
        8.0,
        universe,
        Separator(Orientation.VERTICAL).apply { styleClass += "toolbar-action-separator" },
        refresh, settings, importStatus, importTrades,
        Separator(Orientation.VERTICAL).apply { styleClass += "toolbar-action-separator" },
        about
    ).apply {
        alignment = Pos.CENTER_RIGHT
        styleClass += "title-actions"
    }
}
