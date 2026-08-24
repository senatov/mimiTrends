package org.senatov.mimitrends

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
