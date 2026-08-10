package org.senatov.mimitrends

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.control.Tooltip
import javafx.scene.image.Image
import javafx.scene.image.ImageView
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
        researchButton: Button,
        aboutButton: Button,
        scannerPanel: ScannerPanel,
        trendChart: TrendChartView,
        contentSplitPane: SplitPane,
        requestStatus: RequestStatusPane,
        initialDivider: Double
    ): Parent {
        val titleIdentity = HBox(6.0,
            Label("MiMiTrends").apply { styleClass += "app-title" },
            Label("v${BuildInfo.version}").apply { styleClass += "app-version" }
        ).apply { alignment = Pos.BASELINE_LEFT }
        val titleActions = HBox(8.0, refreshButton, settingsButton, importButton, researchButton, aboutButton).apply {
            alignment = Pos.CENTER_RIGHT
        }
        val titleBar = StackPane(titleIdentity, buildBadge(), titleActions).apply {
            styleClass += "title-toolbar"
            StackPane.setAlignment(titleIdentity, Pos.CENTER_LEFT)
            StackPane.setAlignment(children[1], Pos.CENTER)
            StackPane.setAlignment(titleActions, Pos.CENTER_RIGHT)
        }
        contentSplitPane.apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            items.setAll(scannerPanel, trendChart)
            SplitPane.setResizableWithParent(scannerPanel, true)
            SplitPane.setResizableWithParent(trendChart, true)
            styleClass += "content-split-pane"
        }
        Platform.runLater { contentSplitPane.setDividerPosition(0, initialDivider) }
        val content = VBox(contentSplitPane).apply {
            padding = Insets(22.0, 24.0, 16.0, 24.0)
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

    private fun buildBadge(): HBox {
        val icon = MainViewFactory::class.java.getResourceAsStream("/icons/icon_128x128.png")?.use { stream ->
            ImageView(Image(stream)).apply {
                fitWidth = 30.0; fitHeight = 30.0; isPreserveRatio = true
                styleClass += "build-badge-icon"
            }
        } ?: ImageView()
        return HBox(7.0, icon, VBox(0.0,
            Label(BuildInfo.buildType).apply { styleClass += "build-badge-type" },
            Label("${BuildInfo.buildTime} · #${BuildInfo.buildNumber} at Host: ${BuildInfo.buildHost}").apply {
                styleClass += "build-badge-details"
            }
        ).apply { alignment = Pos.CENTER_LEFT }).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "build-badge"
            Tooltip.install(this, Tooltip("MiMiTrends ${BuildInfo.displayVersion}\nBuilt ${BuildInfo.buildTime} on ${BuildInfo.buildHost}"))
        }
    }
}
