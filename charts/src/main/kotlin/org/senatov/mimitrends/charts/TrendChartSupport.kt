package org.senatov.mimitrends.charts

import javafx.scene.input.MouseEvent
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.AxisLocation
import org.jfree.chart.axis.DateAxis
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.fx.ChartViewer
import org.jfree.chart.fx.interaction.ChartMouseEventFX
import org.jfree.chart.fx.interaction.ChartMouseListenerFX
import org.jfree.chart.labels.HighLowItemLabelGenerator
import org.jfree.chart.labels.StandardXYToolTipGenerator
import org.jfree.chart.plot.CombinedDomainXYPlot
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.CandlestickRenderer
import org.jfree.chart.renderer.xy.XYBarRenderer
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Paint
import java.awt.geom.Point2D
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import kotlin.math.ceil

internal object TrendChartSupport {
    private const val SESSION_GAP_SECONDS = 2 * 60 * 60L

    fun applyTheme(
        dark: Boolean,
        chart: JFreeChart,
        dateAxis: DateAxis,
        priceAxis: NumberAxis,
        volumeAxis: NumberAxis,
        pricePlot: XYPlot,
        volumePlot: XYPlot,
        combinedPlot: CombinedDomainXYPlot
    ) {
        val background = if (dark) Color(23, 30, 38) else Color(247, 248, 250)
        val plotBackground = if (dark) Color(27, 35, 44) else Color.WHITE
        val labels = if (dark) Color(184, 197, 210) else Color(82, 94, 106)
        val axisLine = if (dark) Color(67, 81, 96) else Color(190, 198, 207)
        val domainGrid = if (dark) Color(47, 59, 72) else Color(232, 235, 239)
        val rangeGrid = if (dark) Color(55, 68, 82) else Color(218, 223, 229)
        chart.backgroundPaint = background
        combinedPlot.backgroundPaint = background
        listOf(dateAxis, priceAxis, volumeAxis).forEach { axis ->
            axis.tickLabelPaint = labels
            axis.axisLinePaint = axisLine
            axis.labelPaint = labels
        }
        listOf(pricePlot, volumePlot).forEach { plot ->
            plot.backgroundPaint = plotBackground
            plot.domainGridlinePaint = domainGrid
            plot.rangeGridlinePaint = rangeGrid
        }
        chart.fireChartChanged()
    }

    fun configure(
        chart: JFreeChart,
        viewer: ChartViewer,
        dateAxis: DateAxis,
        priceAxis: NumberAxis,
        volumeAxis: NumberAxis,
        candleRenderer: CandlestickRenderer,
        volumeRenderer: DirectionalVolumeRenderer,
        pricePlot: XYPlot,
        volumePlot: XYPlot,
        combinedPlot: CombinedDomainXYPlot,
        onCursorMoved: (Double, Double) -> Unit,
        onCursorClicked: (Double, Double) -> Unit
    ) {
        val background = Color(247, 248, 250)
        val domainGrid = Color(232, 235, 239)
        val rangeGrid = Color(218, 223, 229)
        chart.backgroundPaint = background
        chart.setAntiAlias(true)
        chart.title.isVisible = false
        dateAxis.lowerMargin = 0.01
        dateAxis.upperMargin = 0.01
        dateAxis.isAutoTickUnitSelection = true
        dateAxis.tickLabelFont = Font("Dialog", Font.PLAIN, 12)
        dateAxis.tickLabelPaint = Color(82, 94, 106)
        dateAxis.axisLinePaint = Color(190, 198, 207)
        priceAxis.autoRangeIncludesZero = false
        priceAxis.lowerMargin = 0.04
        priceAxis.upperMargin = 0.04
        volumeAxis.autoRangeIncludesZero = true
        pricePlot.rangeAxisLocation = AxisLocation.BOTTOM_OR_RIGHT
        volumePlot.rangeAxisLocation = AxisLocation.BOTTOM_OR_RIGHT
        listOf(priceAxis, volumeAxis).forEach { axis ->
            axis.tickLabelFont = Font("Dialog", Font.PLAIN, 12)
            axis.tickLabelPaint = Color(82, 94, 106)
            axis.axisLinePaint = Color(190, 198, 207)
        }
        candleRenderer.upPaint = Color(38, 148, 92)
        candleRenderer.downPaint = Color(211, 70, 82)
        candleRenderer.useOutlinePaint = true
        candleRenderer.defaultOutlinePaint = Color(70, 75, 81)
        candleRenderer.defaultStroke = BasicStroke(1.0f)
        candleRenderer.defaultToolTipGenerator = HighLowItemLabelGenerator(
            SimpleDateFormat("dd.MM.yyyy HH:mm"), DecimalFormat("#,##0.00")
        )
        candleRenderer.autoWidthMethod = CandlestickRenderer.WIDTHMETHOD_SMALLEST
        candleRenderer.autoWidthFactor = 0.72
        candleRenderer.drawVolume = false
        volumeRenderer.setShadowVisible(false)
        volumeRenderer.margin = 0.12
        volumeRenderer.defaultToolTipGenerator = StandardXYToolTipGenerator(
            "{0}: {1}  {2}", SimpleDateFormat("dd.MM.yyyy HH:mm"), DecimalFormat("#,##0")
        )
        listOf(pricePlot, volumePlot).forEach { plot ->
            plot.backgroundPaint = Color.WHITE
            plot.domainGridlinePaint = domainGrid
            plot.rangeGridlinePaint = rangeGrid
            plot.domainGridlineStroke = BasicStroke(0.5f)
            plot.rangeGridlineStroke = BasicStroke(0.6f)
            plot.isDomainPannable = true
            plot.isRangePannable = true
        }
        combinedPlot.gap = 4.0
        combinedPlot.orientation = PlotOrientation.VERTICAL
        combinedPlot.add(pricePlot, 4)
        combinedPlot.add(volumePlot, 1)
        combinedPlot.backgroundPaint = background
        viewer.addChartMouseListener(object : ChartMouseListenerFX {
            override fun chartMouseClicked(event: ChartMouseEventFX) {
                onCursorClicked(event.trigger.x, event.trigger.y)
            }

            override fun chartMouseMoved(event: ChartMouseEventFX) {
                val trigger: MouseEvent = event.trigger
                viewer.canvas.setAnchor(Point2D.Double(trigger.x, trigger.y))
                onCursorMoved(trigger.x, trigger.y)
            }
        })
    }

    fun aggregate(bars: List<MinuteBar>, maxCandles: Int): List<MinuteBar> {
        require(maxCandles > 0) { "maxCandles must be positive" }
        if (bars.size <= maxCandles) return bars
        val sessions = splitSessions(bars)
        if (sessions.size >= maxCandles) {
            return evenlySample(sessions, maxCandles).map(::aggregateChunk)
        }
        val allocations = IntArray(sessions.size) { 1 }
        repeat(maxCandles - sessions.size) {
            val index = sessions.indices
                .filter { allocations[it] < sessions[it].size }
                .maxByOrNull { sessions[it].size.toDouble() / allocations[it] }
                ?: return@repeat
            allocations[index]++
        }
        return sessions.flatMapIndexed { index, session ->
            val chunkSize = ceil(session.size / allocations[index].toDouble()).toInt().coerceAtLeast(1)
            session.chunked(chunkSize).map(::aggregateChunk)
        }
    }

    private fun splitSessions(bars: List<MinuteBar>): List<List<MinuteBar>> {
        val sessions = mutableListOf<MutableList<MinuteBar>>()
        bars.forEach { bar ->
            val current = sessions.lastOrNull()
            if (current == null || bar.minuteEpochSeconds - current.last().minuteEpochSeconds >= SESSION_GAP_SECONDS) {
                sessions += mutableListOf(bar)
            } else {
                current += bar
            }
        }
        return sessions
    }

    private fun evenlySample(sessions: List<List<MinuteBar>>, limit: Int): List<List<MinuteBar>> {
        if (limit == 1) return listOf(sessions.last())
        return (0 until limit).map { index ->
            sessions[index * (sessions.lastIndex) / (limit - 1)]
        }
    }

    private fun aggregateChunk(chunk: List<MinuteBar>): MinuteBar {
        val first = chunk.first()
        val last = chunk.last()
        return MinuteBar(
            first.symbol, last.minuteEpochSeconds, first.open, chunk.maxOf { it.high },
            chunk.minOf { it.low }, last.close, chunk.sumOf { it.volume },
            VolumeStatus.aggregate(chunk.map(MinuteBar::volumeStatus))
        )
    }

    fun inferBarWidth(bars: List<MinuteBar>): Double {
        if (bars.size < 2) return 48_000.0
        val intervals = bars.zipWithNext { first, second ->
            (second.minuteEpochSeconds - first.minuteEpochSeconds).coerceAtLeast(1) * 1_000.0
        }.sorted()
        return intervals[intervals.size / 2] * 0.82
    }

    fun datePattern(bars: List<MinuteBar>): String {
        val span = bars.last().minuteEpochSeconds - bars.first().minuteEpochSeconds
        return when {
            span <= 86_400 -> "HH:mm"
            span <= 7 * 86_400 -> "dd.MM HH:mm"
            span <= 180 * 86_400 -> "dd.MM"
            else -> "MM.yy"
        }
    }

    fun compactVolume(value: Double): String = when {
        value >= 1_000_000 -> "%.2fM".format(value / 1_000_000.0)
        value >= 1_000 -> "%.1fK".format(value / 1_000.0)
        else -> "%,.0f".format(value)
    }
}

internal class DirectionalVolumeRenderer : XYBarRenderer() {
    var directions: List<Int> = emptyList()
    var signalColumn: Int? = null

    override fun getItemPaint(row: Int, column: Int): Paint = when {
        column == signalColumn -> Color(231, 132, 36, 225)
        directions.getOrNull(column) == 1 -> Color(38, 148, 92, 105)
        directions.getOrNull(column) == -1 -> Color(211, 70, 82, 105)
        else -> Color(132, 141, 151, 85)
    }

    private companion object { const val serialVersionUID = 1L }
}