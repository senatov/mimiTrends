package org.senatov.mimitrends.charts

import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.StackPane
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.DateAxis
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.fx.ChartViewer
import org.jfree.chart.labels.HighLowItemLabelGenerator
import org.jfree.chart.labels.StandardXYToolTipGenerator
import org.jfree.chart.plot.CombinedDomainXYPlot
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.CandlestickRenderer
import org.jfree.chart.renderer.xy.XYBarRenderer
import org.jfree.data.time.Millisecond
import org.jfree.data.time.TimeSeries
import org.jfree.data.time.TimeSeriesCollection
import org.jfree.data.xy.DefaultHighLowDataset
import org.jfree.data.xy.XYBarDataset
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.ceil

class TrendChartView : StackPane() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateAxis = DateAxis()
    private val priceAxis = NumberAxis()
    private val volumeAxis = NumberAxis("Volume")
    private val candleRenderer = CandlestickRenderer()
    private val volumeRenderer = XYBarRenderer()
    private val pricePlot = XYPlot(null, null, priceAxis, candleRenderer)
    private val volumePlot = XYPlot(null, null, volumeAxis, volumeRenderer)
    private val combinedPlot = CombinedDomainXYPlot(dateAxis)
    private val chart = JFreeChart("Select a scanner result", Font("SansSerif", Font.PLAIN, 14), combinedPlot, false)
    private val viewer = ChartViewer(chart)
    private val progress = ProgressIndicator()

    init {
        log.debug(LogTag.UI, "init()")
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
        configureChart()
        viewer.minWidth = 0.0
        viewer.minHeight = 0.0
        viewer.maxWidth = Double.MAX_VALUE
        viewer.maxHeight = Double.MAX_VALUE
        progress.maxWidth = 32.0
        progress.maxHeight = 32.0
        progress.isVisible = false
        children += listOf(viewer, progress)
    }

    fun renderMinuteBars(
        symbol: String,
        bars: List<MinuteBar>,
        rangeLabel: String,
        priceMultiplier: Double = 1.0,
        currencySymbol: String = "$"
    ) {
        log.debug(LogTag.UI, "renderMinuteBars(symbol={}, bars={}, range={})", symbol, bars.size, rangeLabel)
        val visible = aggregate(bars.sortedBy { it.minuteEpochSeconds })
        if (visible.isEmpty()) {
            clear()
            return
        }

        val dates = Array(visible.size) { Date(visible[it].minuteEpochSeconds * 1_000) }
        val highs = DoubleArray(visible.size) { visible[it].high * priceMultiplier }
        val lows = DoubleArray(visible.size) { visible[it].low * priceMultiplier }
        val opens = DoubleArray(visible.size) { visible[it].open * priceMultiplier }
        val closes = DoubleArray(visible.size) { visible[it].close * priceMultiplier }
        val volumes = DoubleArray(visible.size) { visible[it].volume }
        pricePlot.dataset = DefaultHighLowDataset(symbol, dates, highs, lows, opens, closes, volumes)

        val volumeSeries = TimeSeries("Volume")
        visible.forEach { bar -> volumeSeries.addOrUpdate(Millisecond(Date(bar.minuteEpochSeconds * 1_000)), bar.volume) }
        val barWidthMillis = inferBarWidth(visible)
        volumePlot.dataset = XYBarDataset(TimeSeriesCollection(volumeSeries), barWidthMillis)

        dateAxis.dateFormatOverride = SimpleDateFormat(datePattern(rangeLabel))
        priceAxis.numberFormatOverride = DecimalFormat("$currencySymbol#,##0.00")
        chart.title.text = "$symbol  ·  OHLC  ·  $rangeLabel  ·  ${bars.size} minute bars"
        chart.fireChartChanged()
    }

    fun setLoading(loading: Boolean) {
        log.debug(LogTag.UI, "setLoading(loading={})", loading)
        progress.isVisible = loading
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        pricePlot.dataset = null
        volumePlot.dataset = null
        chart.title.text = "No collected market data"
        chart.fireChartChanged()
    }

    private fun configureChart() {
        log.debug(LogTag.UI, "configureChart()")
        val background = Color(250, 250, 251)
        val plotBackground = Color.WHITE
        val grid = Color(225, 229, 234)
        chart.backgroundPaint = background
        chart.setAntiAlias(true)
        chart.title.font = Font("SansSerif", Font.PLAIN, 14)
        chart.title.paint = Color(66, 70, 76)

        dateAxis.lowerMargin = 0.01
        dateAxis.upperMargin = 0.01
        dateAxis.tickLabelFont = Font("SansSerif", Font.PLAIN, 10)
        dateAxis.tickLabelPaint = Color(100, 108, 116)
        dateAxis.axisLinePaint = Color(170, 178, 186)

        priceAxis.autoRangeIncludesZero = false
        priceAxis.lowerMargin = 0.04
        priceAxis.upperMargin = 0.04
        volumeAxis.autoRangeIncludesZero = true
        listOf(priceAxis, volumeAxis).forEach { axis ->
            axis.tickLabelFont = Font("SansSerif", Font.PLAIN, 10)
            axis.tickLabelPaint = Color(100, 108, 116)
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

        volumeRenderer.setShadowVisible(false)
        volumeRenderer.margin = 0.12
        volumeRenderer.defaultPaint = Color(104, 155, 207)
        volumeRenderer.defaultToolTipGenerator = StandardXYToolTipGenerator(
            "{0}: {1}  {2}", SimpleDateFormat("dd MMM HH:mm"), DecimalFormat("#,##0")
        )

        listOf(pricePlot, volumePlot).forEach { plot ->
            plot.backgroundPaint = plotBackground
            plot.domainGridlinePaint = grid
            plot.rangeGridlinePaint = grid
            plot.isDomainPannable = true
            plot.isRangePannable = true
            plot.isDomainCrosshairVisible = true
            plot.isRangeCrosshairVisible = true
            plot.domainCrosshairPaint = Color(242, 154, 56)
            plot.rangeCrosshairPaint = Color(242, 154, 56)
            plot.domainCrosshairStroke = BasicStroke(1.0f)
            plot.rangeCrosshairStroke = BasicStroke(1.0f)
        }
        combinedPlot.gap = 4.0
        combinedPlot.orientation = PlotOrientation.VERTICAL
        combinedPlot.add(pricePlot, 4)
        combinedPlot.add(volumePlot, 1)
        combinedPlot.backgroundPaint = background
    }

    private fun aggregate(bars: List<MinuteBar>): List<MinuteBar> {
        log.debug(LogTag.UI, "aggregate(bars={})", bars.size)
        val chunkSize = ceil(bars.size / MAX_CANDLES.toDouble()).toInt().coerceAtLeast(1)
        return bars.chunked(chunkSize).map { chunk ->
            val first = chunk.first()
            val last = chunk.last()
            MinuteBar(
                first.symbol, last.minuteEpochSeconds, first.open,
                chunk.maxOf { it.high }, chunk.minOf { it.low }, last.close,
                chunk.sumOf { it.volume }
            )
        }
    }

    private fun inferBarWidth(bars: List<MinuteBar>): Double {
        log.debug(LogTag.UI, "inferBarWidth(bars={})", bars.size)
        if (bars.size < 2) return 48_000.0
        val intervals = bars.zipWithNext { first, second ->
            (second.minuteEpochSeconds - first.minuteEpochSeconds).coerceAtLeast(1) * 1_000.0
        }.sorted()
        return intervals[intervals.size / 2] * 0.82
    }

    private fun datePattern(rangeLabel: String): String {
        log.debug(LogTag.UI, "datePattern(range={})", rangeLabel)
        return when (rangeLabel) {
            "1D" -> "HH:mm"
            "5D" -> "dd MMM HH:mm"
            "1M", "3M", "6M" -> "dd MMM"
            else -> "MMM yy"
        }
    }

    private companion object {
        const val MAX_CANDLES = 360
    }
}
