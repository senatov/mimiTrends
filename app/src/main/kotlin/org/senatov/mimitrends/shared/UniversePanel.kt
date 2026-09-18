package org.senatov.mimitrends.shared

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

import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

internal class UniversePanel : VBox(5.0) {
    private val summary = Label("Universe is waiting for its first refresh").apply {
        isWrapText = true
        styleClass += "universe-summary"
    }
    private val entries = VBox(3.0)

    init {
        children.setAll(summary, ScrollPane(entries).apply {
            isFitToWidth = true
            styleClass += "universe-scroll"
            VBox.setVgrow(this, Priority.ALWAYS)
        })
        styleClass += "universe-panel"
    }

    fun show(selection: DynamicUniverseSelection) {
        val us = selection.symbols.count { !it.contains('.') }
        val europe = selection.symbols.size - us
        summary.text = "US $us/50 · Europe $europe/50 · daily activity-ranked universe"
        entries.children.setAll(selection.symbols.map { symbol ->
            val source = if (symbol in selection.discovered) "dynamic" else "fallback"
            HBox(
                6.0,
                Label("${selection.ranks[symbol] ?: 0}.").apply { styleClass += "universe-rank" },
                Label(symbol).apply { styleClass += "universe-symbol" },
                Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
                Label(source).apply { styleClass += "universe-source" }
            ).apply { styleClass += "universe-row" }
        })
    }
}