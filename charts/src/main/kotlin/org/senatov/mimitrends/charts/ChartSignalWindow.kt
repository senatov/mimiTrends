package org.senatov.mimitrends.charts

import org.jfree.chart.plot.IntervalMarker
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.ui.Layer
import org.jfree.chart.ui.RectangleAnchor
import org.jfree.chart.ui.TextAnchor
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date

internal class ChartSignalWindow(private val pricePlot: XYPlot, private val volumePlot: XYPlot) {
    private var priceMarker: IntervalMarker? = null
    private var volumeMarker: IntervalMarker? = null

    fun show(
        signal: ScanResult?, bar: MinuteBar?, timeline: ChartTimeline,
        priceMultiplier: Double, currencySymbol: String
    ) {
        clear()
        if (signal == null || bar == null) return
        val isTrend = signal.signalSource.startsWith("Trend") || signal.signalSource.startsWith("Steady rise") ||
            signal.signalSource.startsWith("Recovery")
        val isMomentum = signal.signalSource.startsWith("Momentum")
        val isReversal = signal.signalSource.startsWith("V-Reversal")
        val windowMinutes = when {
            isTrend -> signal.signalWindowLabel.filter(Char::isDigit).toIntOrNull() ?: 180
            isMomentum -> 3
            isReversal -> signal.signalWindowLabel.filter(Char::isDigit).toIntOrNull() ?: 5
            else -> 1
        }
        val end = timeline.displayMillis(bar.minuteEpochSeconds)
        val start = timeline.displayMillis(bar.minuteEpochSeconds - windowMinutes * 60L)
        val signalDate = SimpleDateFormat("dd.MM.yyyy HH:mm").format(Date(bar.minuteEpochSeconds * 1_000))
        val label = when {
            isTrend -> "Trend ${signal.signalWindowLabel}"
            isMomentum -> "Early momentum 3m"
            isReversal -> "V-reversal ${signal.signalWindowLabel}"
            else -> "Signal"
        }
        val entry = signal.signalPrice * priceMultiplier
        val caption = "$label · $signalDate · Entry $currencySymbol${"%,.2f".format(entry)}"
        priceMarker = marker(start, end, caption, signal.signalAgeMinutes)
        volumeMarker = marker(start, end, null, signal.signalAgeMinutes)
        pricePlot.addDomainMarker(priceMarker, Layer.BACKGROUND)
        volumePlot.addDomainMarker(volumeMarker, Layer.BACKGROUND)
    }

    fun clear() {
        priceMarker?.let { pricePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        volumeMarker?.let { volumePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        priceMarker = null
        volumeMarker = null
    }

    private fun marker(start: Double, end: Double, caption: String?, ageMinutes: Int): IntervalMarker =
        IntervalMarker(start, end).apply {
            paint = Color(242, 154, 56, 48)
            outlinePaint = Color(224, 124, 31, 150)
            outlineStroke = BasicStroke(1.0f)
            label = caption
            labelFont = Font("SansSerif", Font.PLAIN, 10)
            labelPaint = Color(126, 69, 20)
            labelAnchor = if (ageMinutes == 0) RectangleAnchor.TOP_RIGHT else RectangleAnchor.TOP_LEFT
            labelTextAnchor = if (ageMinutes == 0) TextAnchor.TOP_RIGHT else TextAnchor.TOP_LEFT
        }
}
