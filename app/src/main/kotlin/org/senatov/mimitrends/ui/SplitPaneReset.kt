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

import javafx.application.Platform
import javafx.scene.control.SplitPane
import javafx.scene.input.MouseButton

internal object SplitPaneReset {
    fun install(pane: SplitPane, defaultPosition: Double) {
        Platform.runLater {
            pane.lookupAll(".split-pane-divider").forEach { divider ->
                divider.setOnMouseClicked { event ->
                    if (event.button == MouseButton.PRIMARY && event.clickCount == 2) {
                        pane.setDividerPosition(0, defaultPosition)
                        event.consume()
                    }
                }
            }
        }
    }
}
