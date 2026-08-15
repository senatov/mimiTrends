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

internal class ChartCursorLabelAnnotation(
    private val placement: Placement
) : AbstractXYAnnotation() {
    var value: Double = 0.0
    var text: String = ""
    var placeBeforeLine: Boolean = true

    override fun draw(
        graphics: Graphics2D,
        plot: XYPlot,
        dataArea: Rectangle2D,
        domainAxis: ValueAxis,
        rangeAxis: ValueAxis,
        rendererIndex: Int,
        info: PlotRenderingInfo?
    ) {
        if (text.isEmpty()) return
        graphics.font = LABEL_FONT
        val metrics = graphics.fontMetrics
        val width = metrics.stringWidth(text) + HORIZONTAL_PADDING * 2.0
        val height = metrics.height + VERTICAL_PADDING * 2.0
        val frame = when (placement) {
            Placement.DOMAIN_BOTTOM -> {
                val centerX = domainAxis.valueToJava2D(value, dataArea, plot.domainAxisEdge)
                Rectangle2D.Double(
                    (centerX - width / 2.0).coerceIn(dataArea.minX, dataArea.maxX - width),
                    dataArea.maxY - height,
                    width,
                    height
                )
            }
            Placement.RANGE_RIGHT -> {
                val lineY = rangeAxis.valueToJava2D(value, dataArea, plot.rangeAxisEdge)
                val preferredY = if (placeBeforeLine) lineY - height - LINE_GAP else lineY + LINE_GAP
                Rectangle2D.Double(
                    dataArea.maxX - width,
                    preferredY.coerceIn(dataArea.minY, dataArea.maxY - height),
                    width,
                    height
                )
            }
        }
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

    internal enum class Placement { DOMAIN_BOTTOM, RANGE_RIGHT }

    private companion object {
        private const val serialVersionUID = 1L
        val LABEL_FONT = Font("SansSerif", Font.BOLD, 12)
        val BACKGROUND = Color(255, 249, 207, 245)
        val BORDER = Color(105, 177, 222, 235)
        val TEXT = Color(27, 55, 104)
        val BORDER_STROKE = BasicStroke(1.1f)
        const val HORIZONTAL_PADDING = 7.0
        const val VERTICAL_PADDING = 4.0
        const val LINE_GAP = 4.0
    }
}
