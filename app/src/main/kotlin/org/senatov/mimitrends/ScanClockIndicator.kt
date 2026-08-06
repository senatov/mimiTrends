package org.senatov.mimitrends

import javafx.animation.Animation
import javafx.animation.Interpolator
import javafx.animation.RotateTransition
import javafx.animation.Timeline
import javafx.animation.KeyFrame
import javafx.scene.AccessibleRole
import javafx.scene.control.Tooltip
import javafx.scene.layout.Pane
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.scene.transform.Rotate
import javafx.util.Duration

internal class ScanClockIndicator : Pane() {
    private val minuteHand = Line(CENTER, CENTER, CENTER, 9.0).apply { styleClass += "scan-clock-minute-hand" }
    private val secondHand = Line(CENTER, 16.0, CENTER, 7.0).apply { styleClass += "scan-clock-second-hand" }
    private val minuteRotation = Rotate(0.0, CENTER, CENTER).also(minuteHand.transforms::add)
    private val secondRotation = Rotate(0.0, CENTER, CENTER).also(secondHand.transforms::add)
    private val tooltip = Tooltip()
    private var countdown: Timeline? = null
    private var scanRotation: RotateTransition? = null

    init {
        minWidth = SIZE
        prefWidth = SIZE
        maxWidth = SIZE
        minHeight = SIZE
        prefHeight = SIZE
        maxHeight = SIZE
        styleClass += "scan-clock"
        children += Circle(CENTER, CENTER, 10.0).apply { styleClass += "scan-clock-face" }
        children += minuteHand
        children += secondHand
        children += Circle(CENTER, CENTER, 1.5).apply { styleClass += "scan-clock-center" }
        Tooltip.install(this, tooltip)
        accessibleRole = AccessibleRole.TEXT
        isVisible = false
        isManaged = false
    }

    fun showScanning() {
        stopAnimations()
        show("Scanning market data")
        minuteRotation.angle = 45.0
        scanRotation = RotateTransition(Duration.seconds(1.2), secondHand).apply {
            byAngle = 360.0
            interpolator = Interpolator.LINEAR
            cycleCount = Animation.INDEFINITE
            play()
        }
    }

    fun showCountdown(seconds: Long) {
        stopAnimations()
        var remaining = seconds.coerceAtLeast(0L)
        fun render() {
            minuteRotation.angle = (remaining % 3_600L) / 10.0
            secondRotation.angle = (60L - remaining % 60L) * 6.0
            show("Next scan in %02d:%02d".format(remaining / 60L, remaining % 60L))
        }
        render()
        countdown = Timeline(KeyFrame(Duration.seconds(1.0), {
            remaining = (remaining - 1L).coerceAtLeast(0L)
            render()
        })).apply {
            cycleCount = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            play()
        }
    }

    fun clearIndicator() {
        stopAnimations()
        isVisible = false
        isManaged = false
        accessibleText = ""
    }

    private fun show(description: String) {
        tooltip.text = description
        accessibleText = description
        isVisible = true
        isManaged = true
    }

    private fun stopAnimations() {
        countdown?.stop()
        countdown = null
        scanRotation?.stop()
        scanRotation = null
        secondHand.rotate = 0.0
    }

    private companion object {
        const val SIZE = 30.0
        const val CENTER = SIZE / 2.0
    }
}
