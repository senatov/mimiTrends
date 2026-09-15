package org.senatov.mimitrends

import javafx.animation.Animation
import javafx.animation.FadeTransition
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.HBox
import javafx.util.Duration

/** Covers the toolbar until the first analytical pass has been published. */
internal class MarketAnalysisStartupOverlay : HBox(12.0) {
    private val message = Label("Please wait — collecting market analytics…").apply {
        styleClass += "startup-analysis-message"
        isWrapText = true
        minWidth = 0.0
    }
    private val progress = ProgressIndicator().apply {
        prefWidth = 26.0
        prefHeight = 26.0
        maxWidth = 26.0
        maxHeight = 26.0
    }
    private val pulse = FadeTransition(Duration.seconds(1.1), message).apply {
        fromValue = 1.0
        toValue = 0.5
        isAutoReverse = true
        cycleCount = Animation.INDEFINITE
    }

    init {
        alignment = Pos.CENTER
        styleClass += "startup-analysis-overlay"
        children.addAll(progress, message)
        sceneProperty().addListener { _, _, scene ->
            if (scene != null && isVisible) pulse.play() else pulse.stop()
        }
    }

    fun resume() {
        if (!isVisible) return
        message.text = "Please wait — collecting market analytics…"
        progress.isVisible = true
        progress.isManaged = true
        if (scene != null) pulse.play()
    }

    fun finish() {
        pulse.stop()
        isVisible = false
        isManaged = false
    }

    fun showFailure() {
        if (!isVisible) return
        pulse.stop()
        message.opacity = 1.0
        message.text = "Market analytics unavailable — waiting for the next scan"
        progress.isVisible = false
        progress.isManaged = false
    }
}
