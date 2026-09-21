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
import org.senatov.mimitrends.model.ScanResult

internal class InsightSidebar(
    positiveWatch: ModerateCandidatePanel
) : TabPane() {
    private val inspector = SignalInspectorPanel()
    private val universe = UniversePanel()
    private val positiveWatchTab = Tab().apply {
        isClosable = false
        content = positiveWatch
    }

    init {
        tabs.setAll(
            positiveWatchTab,
            Tab("Signal", inspector),
            Tab("Universe", universe)
        )
        tabs.forEach { it.isClosable = false }
        tabClosingPolicy = TabClosingPolicy.UNAVAILABLE
        minWidth = 310.0
        prefWidth = 330.0
        maxWidth = 390.0
        styleClass += "insight-sidebar"
        positiveWatch.setCountListener { count ->
            positiveWatchTab.text = "Positive watch ($count)"
        }
    }

    fun showSignal(result: ScanResult) {
        inspector.show(result)
        selectionModel.select(1)
    }

    fun showUniverse(selection: DynamicUniverseSelection) = universe.show(selection)
}
