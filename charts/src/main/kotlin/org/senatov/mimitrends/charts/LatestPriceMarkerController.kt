package org.senatov.mimitrends.charts

import org.jfree.chart.plot.ValueMarker
import org.jfree.chart.plot.XYPlot
import java.awt.BasicStroke
import java.awt.Color
import java.text.DecimalFormat

internal class LatestPriceMarkerController(private val plot: XYPlot) {
    private val marker = ValueMarker(0.0)
    private var label: LatestPriceLabelAnnotation? = null
    private var installed = false

    fun show(value: Double, currencySymbol: String) {
        marker.value = value
        marker.label = null
        marker.paint = Color(20, 151, 137, 190)
        marker.stroke = BasicStroke(1.2f)
        if (!installed) {
            plot.addRangeMarker(marker)
            installed = true
        }
        label?.let(plot::removeAnnotation)
        val text = "Current / exit  ${DecimalFormat("$currencySymbol#,##0.00").format(value)}"
        label = LatestPriceLabelAnnotation(value, text).also(plot::addAnnotation)
    }

    fun clear() {
        if (installed) plot.removeRangeMarker(marker)
        label?.let(plot::removeAnnotation)
        label = null
        installed = false
    }
}
