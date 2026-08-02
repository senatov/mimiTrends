package org.senatov.mimitrends

import javafx.animation.Interpolator
import javafx.animation.RotateTransition
import javafx.animation.ScaleTransition
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.util.Duration

object ToolbarIconButton {
    fun configure(button: Button, tooltipText: String, rotateOnHover: Boolean = false) {
        button.styleClass += "toolbar-icon-button"
        button.tooltip = Tooltip(tooltipText).apply {
            showDelay = Duration.millis(350.0)
            hideDelay = Duration.millis(120.0)
            styleClass += "mimi-tooltip"
        }
        val scale = ScaleTransition(Duration.millis(150.0), button).apply {
            interpolator = Interpolator.EASE_BOTH
        }
        fun animateScale(target: Double) {
            scale.stop()
            scale.toX = target
            scale.toY = target
            scale.playFromStart()
        }
        button.setOnMouseEntered {
            animateScale(1.08)
            if (rotateOnHover) rotate(button, Duration.millis(240.0), byAngle = 24.0)
        }
        button.setOnMouseExited {
            animateScale(1.0)
            if (rotateOnHover) rotate(button, Duration.millis(180.0), toAngle = 0.0)
        }
        button.setOnMousePressed { button.scaleX = 0.94; button.scaleY = 0.94 }
        button.setOnMouseReleased { button.scaleX = 1.08; button.scaleY = 1.08 }
    }

    private fun rotate(button: Button, duration: Duration, byAngle: Double = 0.0, toAngle: Double = Double.NaN) {
        RotateTransition(duration, button).apply {
            if (toAngle.isNaN()) this.byAngle = byAngle else this.toAngle = toAngle
            interpolator = Interpolator.EASE_BOTH
            play()
        }
    }
}
