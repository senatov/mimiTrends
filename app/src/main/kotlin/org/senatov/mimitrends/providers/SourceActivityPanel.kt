package org.senatov.mimitrends.providers

import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Duration

internal class SourceActivityPanel(
    private val activity: SourceActivity,
    onScalableLogin: () -> Unit = {}
) : VBox(8.0) {
    private val summary = Label().apply { styleClass += "source-panel-summary" }
    private val cards = activity.snapshot().associate { snapshot ->
        snapshot.source to SourceActivityCard(snapshot.source, onScalableLogin)
    }
    private val timer = Timeline(KeyFrame(Duration.seconds(5.0), javafx.event.EventHandler { refresh() })).apply {
        cycleCount = Timeline.INDEFINITE
    }

    init {
        styleClass += listOf("table-section", "source-activity-panel")
        val header = HBox(
            8.0,
            Label("SOURCE TELEMETRY").apply { styleClass += "source-panel-title" },
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            summary
        ).apply {
            styleClass += "source-panel-header"
            alignment = Pos.CENTER_LEFT
        }
        val list = VBox(7.0).apply { children.setAll(cards.values) }
        val scroll = ScrollPane(list).apply {
            styleClass += "source-panel-scroll"
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        }
        VBox.setVgrow(scroll, Priority.ALWAYS)
        children.setAll(header, scroll)
        minWidth = 350.0
        refresh()
        sceneProperty().addListener { _, _, scene -> if (scene == null) timer.stop() else timer.play() }
    }

    private fun refresh() {
        val nowMillis = System.currentTimeMillis()
        val snapshots = activity.snapshot()
        snapshots.forEach { snapshot -> cards[snapshot.source]?.update(snapshot, nowMillis) }
        val active = snapshots.count { SourceActivityPresentation.health(it, nowMillis) == SourceHealth.LIVE }
        val attention = snapshots.count { SourceActivityPresentation.health(it, nowMillis) == SourceHealth.ATTENTION }
        summary.text = "$active online · $attention attention"
        summary.tooltip = Tooltip("${snapshots.size} configured sources · counters cover this app session")
    }
}
