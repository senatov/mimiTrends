package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.AbstractXYAnnotation
import org.jfree.chart.axis.ValueAxis
import org.jfree.chart.plot.PlotRenderingInfo
import org.jfree.chart.plot.XYPlot
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D

internal class LatestPriceLabelAnnotation(
    private val value: Double,
    private val text: String
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
        val lineY = rangeAxis.valueToJava2D(value, dataArea, plot.rangeAxisEdge)
        graphics.font = LABEL_FONT
        val metrics = graphics.fontMetrics
        val width = metrics.stringWidth(text) + HORIZONTAL_PADDING * 2.0
        val height = metrics.height + VERTICAL_PADDING * 2.0
        val x = dataArea.maxX - width - EDGE_GAP
        val aboveY = lineY - LINE_GAP - height
        val y = if (aboveY >= dataArea.minY + EDGE_GAP) {
            aboveY
        } else {
            (lineY + LINE_GAP).coerceAtMost(dataArea.maxY - height - EDGE_GAP)
        }
        val frame = Rectangle2D.Double(
            x.coerceAtLeast(dataArea.minX + EDGE_GAP),
            y,
            width.coerceAtMost(dataArea.width - EDGE_GAP * 2.0),
            height
        )
        val previousHint = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = BACKGROUND
        graphics.fill(frame)
        graphics.color = BORDER
        graphics.stroke = BORDER_STROKE
        graphics.draw(frame)
        graphics.color = TEXT
        graphics.drawString(
            text,
            (frame.x + HORIZONTAL_PADDING).toFloat(),
            (frame.y + VERTICAL_PADDING + metrics.ascent).toFloat()
        )
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousHint)
    }

    private companion object {
        private const val serialVersionUID = 1L
        val LABEL_FONT = Font("SansSerif", Font.BOLD, 11)
        val BACKGROUND = Color(250, 252, 252, 245)
        val BORDER = Color(20, 135, 124, 235)
        val TEXT = Color(17, 105, 97)
        val BORDER_STROKE = BasicStroke(1.2f)
        const val HORIZONTAL_PADDING = 6.0
        const val VERTICAL_PADDING = 3.0
        const val EDGE_GAP = 5.0
        const val LINE_GAP = 4.0
    }
}
