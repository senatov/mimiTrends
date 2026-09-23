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

import javafx.scene.control.Tab
import javafx.scene.control.TabPane

internal class InsightSidebar : TabPane() {
    private val universe = UniversePanel()

    init {
        tabs.setAll(Tab("Liquid universe", universe))
        tabs.forEach { it.isClosable = false }
        tabClosingPolicy = TabClosingPolicy.UNAVAILABLE
        minWidth = 310.0
        prefWidth = 330.0
        maxWidth = 390.0
        styleClass += "insight-sidebar"
    }

    fun showUniverse(selection: DynamicUniverseSelection) = universe.show(selection)
}