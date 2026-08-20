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
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
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
        moderateCandidatePanel: ModerateCandidatePanel,
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
        val titleBar = GridPane().apply {
            styleClass += "title-toolbar"
            columnConstraints += listOf(
                toolbarColumn(33.0),
                toolbarColumn(34.0),
                toolbarColumn(33.0)
            )
            add(titleIdentity, 0, 0)
            add(buildBadge(), 1, 0)
            add(titleActions, 2, 0)
            GridPane.setHalignment(titleIdentity, javafx.geometry.HPos.LEFT)
            GridPane.setHalignment(children[1], javafx.geometry.HPos.CENTER)
            GridPane.setHalignment(titleActions, javafx.geometry.HPos.RIGHT)
        }
        contentSplitPane.apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            val chartArea = HBox(8.0, trendChart, moderateCandidatePanel).apply {
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

    private fun toolbarColumn(widthPercent: Double) = ColumnConstraints().apply {
        percentWidth = widthPercent
        hgrow = Priority.ALWAYS
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
            minWidth = Region.USE_PREF_SIZE
            maxWidth = Region.USE_PREF_SIZE
            styleClass += "build-badge"
            Tooltip.install(this, Tooltip("MiMiTrends ${BuildInfo.displayVersion}\nBuilt ${BuildInfo.buildTime} on ${BuildInfo.buildHost}"))
        }
    }
}
