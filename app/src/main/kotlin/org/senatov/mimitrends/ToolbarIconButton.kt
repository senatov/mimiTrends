package org.senatov.mimitrends

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.Pane
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.Stop
import javafx.scene.shape.Arc
import javafx.scene.shape.ArcType
import javafx.scene.shape.Circle
import javafx.scene.shape.Polygon
import javafx.scene.shape.Rectangle
import javafx.util.Duration

object ToolbarIconButton {
    fun configure(button: Button, icon: ToolbarIcon, tooltipText: String) {
        button.styleClass += "toolbar-icon-button"
        button.accessibleText = tooltipText
        button.text = null
        button.graphic = graphic(icon)
        button.contentDisplay = ContentDisplay.GRAPHIC_ONLY
        button.tooltip = Tooltip(tooltipText).apply {
            showDelay = Duration.millis(350.0)
            hideDelay = Duration.millis(120.0)
            styleClass += "mimi-tooltip"
        }
    }

    private fun graphic(icon: ToolbarIcon): Node = when (icon) {
        ToolbarIcon.REFRESH -> refreshIcon()
        ToolbarIcon.SETTINGS -> settingsIcon()
        ToolbarIcon.IMPORT -> importIcon()
        ToolbarIcon.ABOUT -> aboutIcon()
    }

    private fun refreshIcon(): Pane = iconPane().apply {
        children += Arc(13.0, 13.0, 8.5, 8.5, 35.0, 135.0).apply {
            type = ArcType.OPEN; fill = Color.TRANSPARENT; stroke = Color.web("#087BD8"); strokeWidth = 3.0
        }
        children += Arc(13.0, 13.0, 8.5, 8.5, 215.0, 135.0).apply {
            type = ArcType.OPEN; fill = Color.TRANSPARENT; stroke = Color.web("#19A963"); strokeWidth = 3.0
        }
        children += Polygon(20.0, 3.4, 23.2, 10.0, 16.4, 9.0).apply { fill = Color.web("#F59A23") }
        children += Polygon(6.0, 22.6, 2.8, 16.0, 9.6, 17.0).apply { fill = Color.web("#ED4F73") }
    }

    private fun settingsIcon(): Pane = iconPane().apply {
        repeat(8) { index ->
            children += Rectangle(11.2, 1.0, 3.6, 7.0).apply {
                arcWidth = 2.0; arcHeight = 2.0
                fill = if (index % 2 == 0) Color.web("#187BD1") else Color.web("#7358D8")
                transforms += javafx.scene.transform.Rotate(index * 45.0, 13.0, 13.0)
            }
        }
        children += Circle(13.0, 13.0, 8.2, Color.web("#368BD6"))
        children += Circle(13.0, 13.0, 4.2, Color.web("#FFB52E"))
        children += Circle(13.0, 13.0, 2.0, Color.web("#FFF7D5"))
    }

    private fun importIcon(): Pane = iconPane().apply {
        children += Rectangle(4.0, 17.0, 18.0, 5.5).apply {
            arcWidth = 4.0; arcHeight = 4.0; fill = Color.web("#167CC1")
        }
        children += Rectangle(11.2, 3.0, 3.6, 11.5).apply {
            arcWidth = 2.0; arcHeight = 2.0; fill = Color.web("#20A967")
        }
        children += Polygon(6.8, 11.0, 19.2, 11.0, 13.0, 17.4).apply { fill = Color.web("#F39A28") }
        children += Circle(20.0, 19.8, 2.2, Color.web("#ED4F73"))
    }

    private fun aboutIcon(): Pane = iconPane().apply {
        children += Circle(13.0, 13.0, 10.5).apply {
            fill = LinearGradient(0.0, 0.0, 1.0, 1.0, true, CycleMethod.NO_CYCLE,
                Stop(0.0, Color.web("#1C91E8")), Stop(0.55, Color.web("#635BD8")),
                Stop(1.0, Color.web("#E64E91")))
        }
        children += Label("i").apply {
            textFill = Color.WHITE; style = "-fx-font-size: 18px; -fx-font-weight: 700;"
            minWidth = 26.0; minHeight = 26.0; alignment = Pos.CENTER
        }
    }

    private fun iconPane() = Pane().apply {
        minWidth = 26.0; prefWidth = 26.0; maxWidth = 26.0
        minHeight = 26.0; prefHeight = 26.0; maxHeight = 26.0
        isMouseTransparent = true
    }
}

enum class ToolbarIcon { REFRESH, SETTINGS, IMPORT, ABOUT }
