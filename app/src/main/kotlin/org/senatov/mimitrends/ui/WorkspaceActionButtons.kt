package org.senatov.mimitrends.ui

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

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
