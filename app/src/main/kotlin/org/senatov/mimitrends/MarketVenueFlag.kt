package org.senatov.mimitrends

import javafx.scene.canvas.Canvas
import javafx.scene.control.Tooltip
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color

internal object MarketVenueFlag {
    private const val WIDTH = 15.0
    private const val HEIGHT = 10.0

    fun create(visual: MarketVenueVisual): StackPane {
        val canvas = Canvas(WIDTH, HEIGHT)
        val graphics = canvas.graphicsContext2D
        when (visual.country) {
            MarketCountry.DE -> horizontal(graphics, "#171717", "#D71920", "#FFCE00")
            MarketCountry.NL -> horizontal(graphics, "#AE1C28", "#FFFFFF", "#21468B")
            MarketCountry.FR -> vertical(graphics, "#0055A4", "#FFFFFF", "#EF4135")
            MarketCountry.IT -> vertical(graphics, "#009246", "#FFFFFF", "#CE2B37")
            MarketCountry.FI -> finland(graphics)
            MarketCountry.US -> unitedStates(graphics)
        }
        graphics.stroke = Color.web("#6E7781", 0.72)
        graphics.lineWidth = 0.7
        graphics.strokeRect(0.35, 0.35, WIDTH - 0.7, HEIGHT - 0.7)
        return StackPane(canvas).apply {
            minWidth = WIDTH; prefWidth = WIDTH; maxWidth = WIDTH
            minHeight = HEIGHT; prefHeight = HEIGHT; maxHeight = HEIGHT
            accessibleText = "Current quote: ${visual.venue}"
            Tooltip.install(this, Tooltip(accessibleText))
        }
    }

    private fun horizontal(g: javafx.scene.canvas.GraphicsContext, vararg colors: String) {
        val stripe = HEIGHT / colors.size
        colors.forEachIndexed { index, color ->
            g.fill = Color.web(color); g.fillRect(0.0, index * stripe, WIDTH, stripe + 0.2)
        }
    }

    private fun vertical(g: javafx.scene.canvas.GraphicsContext, vararg colors: String) {
        val stripe = WIDTH / colors.size
        colors.forEachIndexed { index, color ->
            g.fill = Color.web(color); g.fillRect(index * stripe, 0.0, stripe + 0.2, HEIGHT)
        }
    }

    private fun finland(g: javafx.scene.canvas.GraphicsContext) {
        g.fill = Color.WHITE; g.fillRect(0.0, 0.0, WIDTH, HEIGHT)
        g.fill = Color.web("#003580")
        g.fillRect(4.0, 0.0, 2.2, HEIGHT); g.fillRect(0.0, 4.0, WIDTH, 2.2)
    }

    private fun unitedStates(g: javafx.scene.canvas.GraphicsContext) {
        val stripe = HEIGHT / 7.0
        repeat(7) { index ->
            g.fill = if (index % 2 == 0) Color.web("#B22234") else Color.WHITE
            g.fillRect(0.0, index * stripe, WIDTH, stripe + 0.2)
        }
        g.fill = Color.web("#3C3B6E"); g.fillRect(0.0, 0.0, 6.5, stripe * 4)
    }
}
