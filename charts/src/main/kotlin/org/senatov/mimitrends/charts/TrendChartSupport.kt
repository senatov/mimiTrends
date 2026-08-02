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
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Paint
import java.awt.geom.Point2D
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import kotlin.math.ceil

internal object TrendChartSupport {
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
        onCursorMoved: (Double, Double) -> Unit
    ) {
        val background = Color(250, 250, 251)
        val grid = Color(196, 204, 213)
        chart.backgroundPaint = background
        chart.setAntiAlias(true)
        chart.title.isVisible = false
        dateAxis.lowerMargin = 0.01
        dateAxis.upperMargin = 0.01
        dateAxis.isAutoTickUnitSelection = true
        dateAxis.tickLabelFont = Font("SansSerif", Font.PLAIN, 11)
        dateAxis.tickLabelPaint = Color(54, 65, 76)
        dateAxis.axisLinePaint = Color(170, 178, 186)
        priceAxis.autoRangeIncludesZero = false
        priceAxis.lowerMargin = 0.04
        priceAxis.upperMargin = 0.04
        volumeAxis.autoRangeIncludesZero = true
        pricePlot.rangeAxisLocation = AxisLocation.BOTTOM_OR_RIGHT
        volumePlot.rangeAxisLocation = AxisLocation.BOTTOM_OR_RIGHT
        listOf(priceAxis, volumeAxis).forEach { axis ->
            axis.tickLabelFont = Font("SansSerif", Font.PLAIN, 11)
            axis.tickLabelPaint = Color(54, 65, 76)
            axis.axisLinePaint = Color(170, 178, 186)
        }
        candleRenderer.upPaint = Color(38, 148, 92)
        candleRenderer.downPaint = Color(211, 70, 82)
        candleRenderer.useOutlinePaint = true
        candleRenderer.defaultOutlinePaint = Color(70, 75, 81)
        candleRenderer.defaultStroke = BasicStroke(1.0f)
        candleRenderer.defaultToolTipGenerator = HighLowItemLabelGenerator(
            SimpleDateFormat("dd MMM yyyy HH:mm"), DecimalFormat("#,##0.00")
        )
        candleRenderer.autoWidthMethod = CandlestickRenderer.WIDTHMETHOD_SMALLEST
        candleRenderer.autoWidthFactor = 0.72
        candleRenderer.drawVolume = false
        volumeRenderer.setShadowVisible(false)
        volumeRenderer.margin = 0.12
        volumeRenderer.defaultToolTipGenerator = StandardXYToolTipGenerator(
            "{0}: {1}  {2}", SimpleDateFormat("dd MMM HH:mm"), DecimalFormat("#,##0")
        )
        listOf(pricePlot, volumePlot).forEach { plot ->
            plot.backgroundPaint = Color.WHITE
            plot.domainGridlinePaint = grid
            plot.rangeGridlinePaint = grid
            plot.domainGridlineStroke = BasicStroke(0.8f)
            plot.rangeGridlineStroke = BasicStroke(0.8f)
            plot.isDomainPannable = true
            plot.isRangePannable = true
        }
        combinedPlot.gap = 4.0
        combinedPlot.orientation = PlotOrientation.VERTICAL
        combinedPlot.add(pricePlot, 4)
        combinedPlot.add(volumePlot, 1)
        combinedPlot.backgroundPaint = background
        viewer.addChartMouseListener(object : ChartMouseListenerFX {
            override fun chartMouseClicked(event: ChartMouseEventFX) = Unit

            override fun chartMouseMoved(event: ChartMouseEventFX) {
                val trigger: MouseEvent = event.trigger
                viewer.canvas.setAnchor(Point2D.Double(trigger.x, trigger.y))
                onCursorMoved(trigger.x, trigger.y)
            }
        })
    }

    fun aggregate(bars: List<MinuteBar>, maxCandles: Int): List<MinuteBar> {
        val chunkSize = ceil(bars.size / maxCandles.toDouble()).toInt().coerceAtLeast(1)
        return bars.chunked(chunkSize).map { chunk ->
            val first = chunk.first()
            val last = chunk.last()
            MinuteBar(first.symbol, last.minuteEpochSeconds, first.open, chunk.maxOf { it.high },
                chunk.minOf { it.low }, last.close, chunk.sumOf { it.volume })
        }
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
            span <= 7 * 86_400 -> "dd MMM  HH:mm"
            span <= 180 * 86_400 -> "dd MMM"
            else -> "MMM yy"
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
