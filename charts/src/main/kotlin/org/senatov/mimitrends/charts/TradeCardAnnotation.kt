package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.AbstractXYAnnotation
import org.jfree.chart.axis.ValueAxis
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.PlotRenderingInfo
import org.jfree.chart.plot.XYPlot
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D

internal class TradeCardAnnotation(
    private val bounds: BrokerTradeAnnotations.CardBounds,
    private val title: String,
    private val detail: String,
    private val detailColor: Color
) : AbstractXYAnnotation() {
    override fun draw(
        graphics: Graphics2D,
        plot: XYPlot,
        dataArea: Rectangle2D,
        domainAxis: ValueAxis,
        rangeAxis: ValueAxis,
        rendererIndex: Int,
        info: PlotRenderingInfo?
    ) {
        val left = domainAxis.valueToJava2D(bounds.left, dataArea, plot.domainAxisEdge)
        val right = domainAxis.valueToJava2D(bounds.right, dataArea, plot.domainAxisEdge)
        val bottom = rangeAxis.valueToJava2D(bounds.bottom, dataArea, plot.rangeAxisEdge)
        val top = rangeAxis.valueToJava2D(bounds.top, dataArea, plot.rangeAxisEdge)
        val card = screenBounds(plot.orientation, left, right, bottom, top)
        val arc = minOf(card.width * 0.08, card.height * 0.72)
        val shape = RoundRectangle2D.Double(card.x, card.y, card.width, card.height, arc, arc)
        val previousHint = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        graphics.color = SHADOW_COLOR
        graphics.fill(RoundRectangle2D.Double(
            card.x + SHADOW_OFFSET, card.y + SHADOW_OFFSET,
            card.width, card.height, arc, arc
        ))
        graphics.color = CARD_FILL
        graphics.fill(shape)
        graphics.color = CARD_BORDER
        graphics.stroke = CARD_STROKE
        graphics.draw(shape)

        val contentWidth = (card.width - HORIZONTAL_PADDING * 2.0).coerceAtLeast(0.0)
        graphics.font = TITLE_FONT
        drawCentered(graphics, ellipsize(graphics, title, contentWidth), card.centerX,
            card.y + card.height * 0.40, TITLE_COLOR, TITLE_FONT)
        graphics.font = DETAIL_FONT
        drawCentered(graphics, ellipsize(graphics, detail, contentWidth), card.centerX,
            card.y + card.height * 0.78, detailColor, DETAIL_FONT)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousHint)
    }

    private fun screenBounds(
        orientation: PlotOrientation,
        left: Double,
        right: Double,
        bottom: Double,
        top: Double
    ): Rectangle2D.Double = if (orientation == PlotOrientation.HORIZONTAL) {
        Rectangle2D.Double(minOf(bottom, top), minOf(left, right),
            kotlin.math.abs(top - bottom), kotlin.math.abs(right - left))
    } else {
        Rectangle2D.Double(minOf(left, right), minOf(bottom, top),
            kotlin.math.abs(right - left), kotlin.math.abs(top - bottom))
    }

    private fun drawCentered(
        graphics: Graphics2D,
        value: String,
        centerX: Double,
        baseline: Double,
        color: Color,
        font: Font
    ) {
        graphics.font = font
        graphics.color = color
        val width = graphics.fontMetrics.stringWidth(value)
        graphics.drawString(value, (centerX - width / 2.0).toFloat(), baseline.toFloat())
    }

    private fun ellipsize(graphics: Graphics2D, value: String, availableWidth: Double): String {
        if (graphics.fontMetrics.stringWidth(value) <= availableWidth) return value
        var end = value.length
        while (end > 0 && graphics.fontMetrics.stringWidth(value.substring(0, end) + ELLIPSIS) > availableWidth) {
            end--
        }
        return if (end == 0) "" else value.substring(0, end).trimEnd() + ELLIPSIS
    }

    private companion object {
        private const val serialVersionUID = 1L
        val CARD_BORDER = Color(91, 72, 126, 215)
        val CARD_FILL = Color(248, 250, 252, 242)
        val SHADOW_COLOR = Color(24, 28, 36, 55)
        val TITLE_COLOR = Color(48, 55, 63)
        val CARD_STROKE = BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val TITLE_FONT = Font("SansSerif", Font.PLAIN, 12)
        val DETAIL_FONT = Font("SansSerif", Font.BOLD, 12)
        const val SHADOW_OFFSET = 3.0
        const val HORIZONTAL_PADDING = 10.0
        const val ELLIPSIS = "…"
    }
}
