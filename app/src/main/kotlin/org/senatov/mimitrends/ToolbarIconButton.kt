package org.senatov.mimitrends

import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.util.Duration

object ToolbarIconButton {
    fun configure(button: Button, tooltipText: String) {
        button.styleClass += "toolbar-icon-button"
        button.accessibleText = tooltipText
        button.tooltip = Tooltip(tooltipText).apply {
            showDelay = Duration.millis(350.0)
            hideDelay = Duration.millis(120.0)
            styleClass += "mimi-tooltip"
        }
    }
}
