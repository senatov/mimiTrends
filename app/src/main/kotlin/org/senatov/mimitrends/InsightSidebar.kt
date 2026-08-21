package org.senatov.mimitrends

import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import org.senatov.mimitrends.model.ScanResult

internal class InsightSidebar(
    positiveWatch: ModerateCandidatePanel
) : TabPane() {
    private val inspector = SignalInspectorPanel()
    private val universe = UniversePanel()

    init {
        tabs.setAll(
            Tab("Positive watch", positiveWatch),
            Tab("Signal", inspector),
            Tab("Universe", universe)
        )
        tabs.forEach { it.isClosable = false }
        tabClosingPolicy = TabClosingPolicy.UNAVAILABLE
        minWidth = 250.0
        prefWidth = 285.0
        maxWidth = 340.0
        styleClass += "insight-sidebar"
    }

    fun showSignal(result: ScanResult) {
        inspector.show(result)
        selectionModel.select(1)
    }

    fun showUniverse(selection: DynamicUniverseSelection) = universe.show(selection)
}
