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
import java.awt.geom.RoundRectangle2D
import java.awt.geom.Rectangle2D

internal class SessionBoundaryDateAnnotation(
    private val value: Double,
    private val previousDate: String,
    private val nextDate: String
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
        val lineX = domainAxis.valueToJava2D(value, dataArea, plot.domainAxisEdge)
        graphics.font = LABEL_FONT
        val metrics = graphics.fontMetrics
        val height = metrics.height + VERTICAL_PADDING * 2.0
        val y = dataArea.minY + TOP_OFFSET
        drawBadge(graphics, previousDate, lineX - LABEL_GAP, y, height, alignRight = true, dataArea)
        drawBadge(graphics, nextDate, lineX + LABEL_GAP, y, height, alignRight = false, dataArea)
    }

    private fun drawBadge(
        graphics: Graphics2D,
        text: String,
        anchorX: Double,
        y: Double,
        height: Double,
        alignRight: Boolean,
        dataArea: Rectangle2D
    ) {
        val width = graphics.fontMetrics.stringWidth(text) + HORIZONTAL_PADDING * 2.0
        val preferredX = if (alignRight) anchorX - width else anchorX
        val x = preferredX.coerceIn(dataArea.minX, dataArea.maxX - width)
        val frame = RoundRectangle2D.Double(x, y, width, height, 7.0, 7.0)
        val previousHint = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.color = BACKGROUND
        graphics.fill(frame)
        graphics.color = BORDER
        graphics.stroke = BORDER_STROKE
        graphics.draw(frame)
        graphics.color = TEXT
        graphics.drawString(
            text, (x + HORIZONTAL_PADDING).toFloat(),
            (y + VERTICAL_PADDING + graphics.fontMetrics.ascent).toFloat()
        )
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previousHint)
    }

    private companion object {
        private const val serialVersionUID = 1L
        val LABEL_FONT = Font("SansSerif", Font.BOLD, 10)
        val BACKGROUND = Color(239, 247, 255, 238)
        val BORDER = Color(42, 111, 178, 210)
        val TEXT = Color(28, 78, 128)
        val BORDER_STROKE = BasicStroke(1.0f)
        const val HORIZONTAL_PADDING = 6.0
        const val VERTICAL_PADDING = 3.0
        const val LABEL_GAP = 4.0
        const val TOP_OFFSET = 5.0
    }
}