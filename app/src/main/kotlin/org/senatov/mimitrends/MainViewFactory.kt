package org.senatov.mimitrends

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.senatov.mimitrends.charts.TrendChartView

internal object MainViewFactory {
    fun create(
        refreshButton: Button,
        settingsButton: Button,
        importButton: Button,
        aboutButton: Button,
        scannerPanel: ScannerPanel,
        trendChart: TrendChartView,
        insightSidebar: InsightSidebarHost,
        contentSplitPane: SplitPane,
        requestStatus: RequestStatusPane,
        initialDivider: Double
    ): Parent {
        val titleIdentity = HBox(6.0,
            Label("MiMiTrends").apply { styleClass += "app-title" },
            Label("v${BuildInfo.version}").apply { styleClass += "app-version" }
        ).apply { alignment = Pos.BASELINE_LEFT }
        val titleActions = HBox(10.0, refreshButton, settingsButton, importButton, aboutButton).apply {
            alignment = Pos.CENTER_RIGHT
        }
        val titleBar = BorderPane().apply {
            styleClass += "title-toolbar"
            left = titleIdentity
            right = titleActions
        }
        contentSplitPane.apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            val chartArea = HBox(8.0, trendChart, insightSidebar).apply {
                HBox.setHgrow(trendChart, Priority.ALWAYS)
                minHeight = 0.0
            }
            items.setAll(scannerPanel, chartArea)
            SplitPane.setResizableWithParent(scannerPanel, true)
            SplitPane.setResizableWithParent(chartArea, true)
            styleClass += "content-split-pane"
        }
        Platform.runLater { contentSplitPane.setDividerPosition(0, initialDivider) }
        val content = VBox(contentSplitPane).apply {
            padding = Insets(12.0, 14.0, 12.0, 14.0)
            VBox.setVgrow(contentSplitPane, Priority.ALWAYS)
        }
        val root = BorderPane(content, VBox(titleBar, requestStatus), null, null, null).apply {
            styleClass += "app-root"
        }
        return StackPane(root, scannerPanel.marketClosedOverlay).apply {
            styleClass += "app-layers"
            StackPane.setAlignment(scannerPanel.marketClosedOverlay, Pos.CENTER)
        }
    }

}
