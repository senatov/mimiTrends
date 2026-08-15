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
        val initialCard = screenBounds(plot.orientation, left, right, bottom, top)
        graphics.font = TITLE_FONT
        val desiredContentWidth = maxOf(
            title.lines().maxOf(graphics.fontMetrics::stringWidth),
            graphics.getFontMetrics(DETAIL_FONT).stringWidth(detail)
        ).toDouble()
        val maximumWidth = (dataArea.width * MAXIMUM_WIDTH_SHARE).coerceAtLeast(initialCard.width)
        val cardWidth = maxOf(initialCard.width, desiredContentWidth + HORIZONTAL_PADDING * 2.0)
            .coerceAtMost(maximumWidth)
        val contentWidth = (cardWidth - HORIZONTAL_PADDING * 2.0).coerceAtLeast(1.0)
        val titleLines = wrapWords(graphics, title, contentWidth, TITLE_FONT)
        val detailLines = wrapWords(graphics, detail, contentWidth, DETAIL_FONT)
        val titleLineHeight = graphics.getFontMetrics(TITLE_FONT).height.toDouble()
        val detailLineHeight = graphics.getFontMetrics(DETAIL_FONT).height.toDouble()
        val cardHeight = maxOf(
            initialCard.height,
            VERTICAL_PADDING * 2.0 + titleLines.size * titleLineHeight + ROW_GAP +
                detailLines.size * detailLineHeight
        )
        val card = fitToDataArea(initialCard.centerX, initialCard.centerY, cardWidth, cardHeight, dataArea)
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

        var baseline = card.y + VERTICAL_PADDING + graphics.getFontMetrics(TITLE_FONT).ascent
        titleLines.forEach { line ->
            drawCentered(graphics, line, card.centerX, baseline, TITLE_COLOR, TITLE_FONT)
            baseline += titleLineHeight
        }
        baseline += ROW_GAP + graphics.getFontMetrics(DETAIL_FONT).ascent
        detailLines.forEach { line ->
            drawCentered(graphics, line, card.centerX, baseline, detailColor, DETAIL_FONT)
            baseline += detailLineHeight
        }
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

    private fun wrapWords(
        graphics: Graphics2D,
        value: String,
        availableWidth: Double,
        font: Font
    ): List<String> {
        graphics.font = font
        val lines = mutableListOf<String>()
        value.lines().forEach { paragraph ->
            var current = ""
            paragraph.split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isNotEmpty() && graphics.fontMetrics.stringWidth(candidate) > availableWidth) {
                    lines += current
                    current = word
                } else {
                    current = candidate
                }
            }
            if (current.isNotEmpty()) lines += current
        }
        return lines.ifEmpty { listOf("") }
    }

    private fun fitToDataArea(
        centerX: Double,
        centerY: Double,
        width: Double,
        height: Double,
        dataArea: Rectangle2D
    ): Rectangle2D.Double {
        val fittedWidth = width.coerceAtMost(dataArea.width)
        val fittedHeight = height.coerceAtMost(dataArea.height)
        val x = (centerX - fittedWidth / 2.0)
            .coerceIn(dataArea.minX, dataArea.maxX - fittedWidth)
        val y = (centerY - fittedHeight / 2.0)
            .coerceIn(dataArea.minY, dataArea.maxY - fittedHeight)
        return Rectangle2D.Double(x, y, fittedWidth, fittedHeight)
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
        const val VERTICAL_PADDING = 7.0
        const val ROW_GAP = 2.0
        const val MAXIMUM_WIDTH_SHARE = 0.90
    }
}
