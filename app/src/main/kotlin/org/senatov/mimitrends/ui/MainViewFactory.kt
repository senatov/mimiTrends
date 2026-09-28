package org.senatov.mimitrends.ui

import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.senatov.mimitrends.scanner.ScannerPanel
import org.senatov.mimitrends.shortmove.ShortMovePanel
import org.senatov.mimitrends.providers.SourceActivityPanel

internal object MainViewFactory {
    fun create(
        actions: WorkspaceActionButtons,
        scannerPanel: ScannerPanel,
        radarPanel: ShortMovePanel,
        sourceActivityPanel: SourceActivityPanel,
        chartDrawer: ChartDrawer,
        contentSplitPane: SplitPane,
        requestStatus: RequestStatusPane,
        initialDivider: Double
    ): Parent {
        val titleIdentity = HBox(Label("MiMiTrends").apply { styleClass += "app-title" }).apply {
            alignment = Pos.BASELINE_LEFT
        }
        val titleBar = BorderPane().apply {
            styleClass += "title-toolbar"
            left = titleIdentity
            right = actions.createToolbar()
        }
        val radarSplitPane = SplitPane(radarPanel, sourceActivityPanel).apply {
            setDividerPositions(0.64)
            styleClass += "radar-split-pane"
            widthProperty().addListener { _, _, width ->
                val next = when {
                    width.toDouble() <= 930.0 -> Orientation.VERTICAL
                    width.toDouble() >= 1_030.0 -> Orientation.HORIZONTAL
                    else -> orientation
                }
                if (orientation != next) {
                    orientation = next
                    setDividerPositions(if (next == Orientation.HORIZONTAL) 0.64 else 0.60)
                }
            }
        }
        contentSplitPane.apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            items.setAll(radarSplitPane, chartDrawer)
            SplitPane.setResizableWithParent(radarSplitPane, true)
            SplitPane.setResizableWithParent(chartDrawer, true)
            styleClass += "content-split-pane"
        }
        chartDrawer.attach(contentSplitPane, initialDivider)
        SplitPaneReset.install(contentSplitPane, initialDivider)
        val content = VBox(contentSplitPane).apply {
            padding = Insets(7.0, 8.0, 8.0, 8.0)
            VBox.setVgrow(contentSplitPane, Priority.ALWAYS)
        }
        val root = BorderPane(content, VBox(StackPane(titleBar, scannerPanel.startupOverlay), requestStatus), null, null, null).apply {
            styleClass += "app-root"
        }
        return StackPane(root, scannerPanel.marketClosedOverlay).apply {
            styleClass += "app-layers"
            StackPane.setAlignment(scannerPanel.marketClosedOverlay, Pos.CENTER)
            WorkspaceShortcuts.install(
                this, mapOf(
                    WorkspaceShortcuts.findSignals to radarPanel::focusSearch,
                    WorkspaceShortcuts.findMoves to radarPanel::focusSearch
                )
            )
        }
    }

}
