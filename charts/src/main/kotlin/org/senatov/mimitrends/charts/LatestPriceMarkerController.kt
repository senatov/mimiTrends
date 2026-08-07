package org.senatov.mimitrends.charts

import org.jfree.chart.plot.ValueMarker
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.ui.RectangleAnchor
import org.jfree.chart.ui.RectangleInsets
import org.jfree.chart.ui.TextAnchor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.text.DecimalFormat

internal class LatestPriceMarkerController(private val plot: XYPlot) {
    private val marker = ValueMarker(0.0)
    private var installed = false

    fun show(value: Double, currencySymbol: String) {
        marker.value = value
        marker.label = "Current / exit  ${DecimalFormat("$currencySymbol#,##0.00").format(value)}"
        marker.paint = Color(20, 151, 137, 190)
        marker.stroke = BasicStroke(1.2f)
        marker.labelFont = Font("SansSerif", Font.BOLD, 11)
        marker.labelPaint = Color.WHITE
        marker.labelBackgroundColor = Color(20, 120, 111, 225)
        marker.labelAnchor = RectangleAnchor.TOP_RIGHT
        marker.labelTextAnchor = TextAnchor.BOTTOM_RIGHT
        marker.labelOffset = RectangleInsets(4.0, 7.0, 4.0, 7.0)
        if (!installed) {
            plot.addRangeMarker(marker)
            installed = true
        }
    }

    fun clear() {
        if (installed) plot.removeRangeMarker(marker)
        installed = false
    }
}
