package org.senatov.mimitrends.charts

import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.geometry.Insets
import javafx.scene.input.MouseEvent
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.DateAxis
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.axis.AxisLocation
import org.jfree.chart.fx.ChartViewer
import org.jfree.chart.fx.interaction.ChartMouseEventFX
import org.jfree.chart.fx.interaction.ChartMouseListenerFX
import org.jfree.chart.labels.HighLowItemLabelGenerator
import org.jfree.chart.labels.StandardXYToolTipGenerator
import org.jfree.chart.plot.CombinedDomainXYPlot
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.ValueMarker
import org.jfree.chart.plot.IntervalMarker
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.CandlestickRenderer
import org.jfree.chart.renderer.xy.XYBarRenderer
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.time.Millisecond
import org.jfree.data.time.TimeSeries
import org.jfree.data.time.TimeSeriesCollection
import org.jfree.data.xy.DefaultHighLowDataset
import org.jfree.data.xy.XYBarDataset
import org.jfree.chart.ui.RectangleAnchor
import org.jfree.chart.ui.RectangleInsets
import org.jfree.chart.ui.RectangleEdge
import org.jfree.chart.ui.TextAnchor
import org.jfree.chart.ui.Layer
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.geom.Point2D
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
    private val closeLineRenderer = XYLineAndShapeRenderer(true, false)
    private val pricePlot = XYPlot(null, null, priceAxis, candleRenderer)
    private val volumePlot = XYPlot(null, null, volumeAxis, volumeRenderer)
    private val combinedPlot = CombinedDomainXYPlot(dateAxis)
    private val chart = JFreeChart("", Font("SansSerif", Font.PLAIN, 14), combinedPlot, false)
    private val viewer = ChartViewer(chart)
    private val progress = ProgressIndicator()
    private val instrumentLabel = Label("Select a scanner result")
    private val chartDetailsLabel = Label("Price and volume history")
    private val priceCursor = ValueMarker(0.0)
    private val priceTimeCursor = ValueMarker(0.0)
    private val volumeTimeCursor = ValueMarker(0.0)
    private var cursorMarkersInstalled = false
    private var cursorDateFormat = SimpleDateFormat("dd MMM HH:mm")
    private var cursorPriceFormat = DecimalFormat("$#,##0.00")
    private var priceSignalMarker: IntervalMarker? = null
    private var volumeSignalMarker: IntervalMarker? = null
    private val latestPriceMarker = ValueMarker(0.0)
    private var latestPriceMarkerInstalled = false

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
        val header = HBox(12.0, instrumentLabel, chartDetailsLabel).apply {
            padding = Insets(7.0, 14.0, 7.0, 14.0)
            style = "-fx-background-color: rgba(248,250,253,0.96); -fx-border-color: transparent transparent #d7dde4 transparent;"
            instrumentLabel.style = "-fx-font-family: 'SF Pro Display'; -fx-font-size: 15px; -fx-font-weight: 400; -fx-text-fill: #1f3c59;"
            chartDetailsLabel.style = "-fx-font-family: 'SF Pro Display'; -fx-font-size: 12px; -fx-font-weight: 300; -fx-text-fill: #667789;"
        }
        val content = VBox(header, viewer)
        VBox.setVgrow(viewer, Priority.ALWAYS)
        children += listOf(content, progress)
    }

    fun renderMinuteBars(
        symbol: String,
        bars: List<MinuteBar>,
        rangeLabel: String,
        priceMultiplier: Double = 1.0,
        currencySymbol: String = "$",
        signalAgeMinutes: Int? = null
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
        val closeSeries = TimeSeries("Close")
        visible.forEach { bar -> closeSeries.addOrUpdate(Millisecond(Date(bar.minuteEpochSeconds * 1_000)), bar.close * priceMultiplier) }
        pricePlot.setDataset(1, TimeSeriesCollection(closeSeries))
        visible.forEach { bar -> volumeSeries.addOrUpdate(Millisecond(Date(bar.minuteEpochSeconds * 1_000)), bar.volume) }
        val barWidthMillis = inferBarWidth(visible)
        volumePlot.dataset = XYBarDataset(TimeSeriesCollection(volumeSeries), barWidthMillis)

        dateAxis.dateFormatOverride = SimpleDateFormat(datePattern(visible))
        priceAxis.numberFormatOverride = DecimalFormat("$currencySymbol#,##0.00")
        cursorDateFormat = SimpleDateFormat("dd MMM yyyy  HH:mm")
        cursorPriceFormat = DecimalFormat("$currencySymbol#,##0.00")
        instrumentLabel.text = "$symbol   $currencySymbol${"%,.2f".format(closes.last())}"
        chartDetailsLabel.text = "$rangeLabel · ${bars.size} minute bars" + signalAgeMinutes?.let {
            " · anomaly ${if (it == 0) "now–5m" else "$it–${it + 5}m ago"}"
        }.orEmpty()
        showLatestPrice(closes.last(), currencySymbol)
        showSignalWindow(visible.last().minuteEpochSeconds, signalAgeMinutes)
        chart.fireChartChanged()
    }

    fun setLoading(loading: Boolean) {
        log.debug(LogTag.UI, "setLoading(loading={})", loading)
        progress.isVisible = loading
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        pricePlot.dataset = null
        pricePlot.setDataset(1, null)
        volumePlot.dataset = null
        if (latestPriceMarkerInstalled) {
            pricePlot.removeRangeMarker(latestPriceMarker)
            latestPriceMarkerInstalled = false
        }
        showSignalWindow(0, null)
        instrumentLabel.text = "No collected market data"
        chartDetailsLabel.text = ""
        chart.fireChartChanged()
    }

    private fun showSignalWindow(latestEpoch: Long, ageMinutes: Int?) {
        log.debug(LogTag.UI, "showSignalWindow(latest={}, age={})", latestEpoch, ageMinutes)
        priceSignalMarker?.let { pricePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        volumeSignalMarker?.let { volumePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        priceSignalMarker = null
        volumeSignalMarker = null
        if (ageMinutes == null || latestEpoch <= 0) return
        val start = (latestEpoch - (ageMinutes + 5) * 60L) * 1_000.0
        val end = (latestEpoch - ageMinutes * 60L) * 1_000.0
        fun marker(label: String?): IntervalMarker = IntervalMarker(start, end).apply {
            paint = Color(242, 154, 56, 48)
            outlinePaint = Color(224, 124, 31, 150)
            outlineStroke = BasicStroke(1.0f)
            this.label = label
            labelFont = Font("SansSerif", Font.PLAIN, 10)
            labelPaint = Color(126, 69, 20)
            labelAnchor = if (ageMinutes == 0) RectangleAnchor.TOP_RIGHT else RectangleAnchor.TOP_LEFT
            labelTextAnchor = if (ageMinutes == 0) TextAnchor.TOP_RIGHT else TextAnchor.TOP_LEFT
        }
        priceSignalMarker = marker("Anomaly ${if (ageMinutes == 0) "now–5m" else "$ageMinutes–${ageMinutes + 5}m ago"}")
        volumeSignalMarker = marker(null)
        pricePlot.addDomainMarker(priceSignalMarker, Layer.BACKGROUND)
        volumePlot.addDomainMarker(volumeSignalMarker, Layer.BACKGROUND)
    }

    private fun showLatestPrice(value: Double, currencySymbol: String) {
        log.debug(LogTag.UI, "showLatestPrice(value={})", value)
        latestPriceMarker.value = value
        latestPriceMarker.label = "Last  ${DecimalFormat("$currencySymbol#,##0.00").format(value)}"
        latestPriceMarker.paint = Color(20, 151, 137, 190)
        latestPriceMarker.stroke = BasicStroke(1.2f)
        latestPriceMarker.labelFont = Font("SansSerif", Font.BOLD, 11)
        latestPriceMarker.labelPaint = Color.WHITE
        latestPriceMarker.labelBackgroundColor = Color(20, 120, 111, 225)
        latestPriceMarker.labelAnchor = RectangleAnchor.TOP_RIGHT
        latestPriceMarker.labelTextAnchor = TextAnchor.BOTTOM_RIGHT
        latestPriceMarker.labelOffset = RectangleInsets(4.0, 7.0, 4.0, 7.0)
        if (!latestPriceMarkerInstalled) {
            pricePlot.addRangeMarker(latestPriceMarker)
            latestPriceMarkerInstalled = true
        }
    }

    private fun configureChart() {
        log.debug(LogTag.UI, "configureChart()")
        val background = Color(250, 250, 251)
        val plotBackground = Color.WHITE
        val grid = Color(225, 229, 234)
        chart.backgroundPaint = background
        chart.setAntiAlias(true)
        chart.title.isVisible = false

        dateAxis.lowerMargin = 0.01
        dateAxis.upperMargin = 0.01
        dateAxis.isTickLabelsVisible = true
        dateAxis.isTickMarksVisible = true
        dateAxis.isAutoTickUnitSelection = true
        dateAxis.tickLabelFont = Font("SansSerif", Font.PLAIN, 11)
        dateAxis.tickLabelPaint = Color(54, 65, 76)
        dateAxis.axisLinePaint = Color(170, 178, 186)

        priceAxis.autoRangeIncludesZero = false
        priceAxis.isTickLabelsVisible = true
        priceAxis.isTickMarksVisible = true
        priceAxis.isAutoTickUnitSelection = true
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

        closeLineRenderer.setSeriesPaint(0, Color(23, 178, 161))
        closeLineRenderer.setSeriesStroke(0, BasicStroke(1.8f))
        closeLineRenderer.defaultToolTipGenerator = StandardXYToolTipGenerator(
            "{0}: {1}  {2}", SimpleDateFormat("dd MMM HH:mm"), DecimalFormat("#,##0.00")
        )
        pricePlot.setRenderer(1, closeLineRenderer)

        volumeRenderer.setShadowVisible(false)
        volumeRenderer.margin = 0.12
        volumeRenderer.setSeriesPaint(0, Color(104, 155, 207, 155))
        volumeRenderer.defaultToolTipGenerator = StandardXYToolTipGenerator(
            "{0}: {1}  {2}", SimpleDateFormat("dd MMM HH:mm"), DecimalFormat("#,##0")
        )

        listOf(pricePlot, volumePlot).forEach { plot ->
            plot.backgroundPaint = plotBackground
            plot.domainGridlinePaint = grid
            plot.rangeGridlinePaint = grid
            plot.isDomainPannable = true
            plot.isRangePannable = true
            plot.isDomainCrosshairVisible = false
            plot.isRangeCrosshairVisible = false
        }
        combinedPlot.gap = 4.0
        combinedPlot.orientation = PlotOrientation.VERTICAL
        combinedPlot.add(pricePlot, 4)
        combinedPlot.add(volumePlot, 1)
        combinedPlot.backgroundPaint = background
        viewer.addChartMouseListener(object : ChartMouseListenerFX {
            override fun chartMouseClicked(event: ChartMouseEventFX) {
                val trigger: MouseEvent = event.trigger
                log.trace(LogTag.UI, "chartMouseClicked(x={}, y={})", trigger.x, trigger.y)
            }

            override fun chartMouseMoved(event: ChartMouseEventFX) {
                val trigger: MouseEvent = event.trigger
                log.trace(LogTag.UI, "chartMouseMoved(x={}, y={})", trigger.x, trigger.y)
                viewer.canvas.setAnchor(Point2D.Double(trigger.x, trigger.y))
                updateCursor(trigger.x, trigger.y)
            }
        })
    }

    private fun updateCursor(x: Double, y: Double) {
        log.trace(LogTag.UI, "updateCursor(x={}, y={})", x, y)
        val plotInfo = viewer.canvas.renderingInfo.plotInfo
        if (plotInfo.subplotCount < 1) return
        val priceArea = plotInfo.getSubplotInfo(0).dataArea
        if (!priceArea.contains(x, y)) return
        val timeValue = dateAxis.java2DToValue(x, priceArea, RectangleEdge.BOTTOM)
        val priceValue = priceAxis.java2DToValue(y, priceArea, pricePlot.rangeAxisEdge)
        if (!timeValue.isFinite() || !priceValue.isFinite()) return
        if (!cursorMarkersInstalled) installCursorMarkers()
        priceTimeCursor.value = timeValue
        volumeTimeCursor.value = timeValue
        priceCursor.value = priceValue
        volumeTimeCursor.label = cursorDateFormat.format(Date(timeValue.toLong()))
        priceCursor.label = cursorPriceFormat.format(priceValue)
        // Keep both labels inside the plot canvas. TextAnchor describes the part of the
        // label placed on the marker anchor, so bottom anchors make the text grow upward.
        volumeTimeCursor.labelTextAnchor = when {
            x < priceArea.minX + 95.0 -> TextAnchor.BOTTOM_LEFT
            x > priceArea.maxX - 95.0 -> TextAnchor.BOTTOM_RIGHT
            else -> TextAnchor.BOTTOM_CENTER
        }
        if (y < priceArea.centerY) {
            priceCursor.labelAnchor = RectangleAnchor.BOTTOM_RIGHT
            priceCursor.labelTextAnchor = TextAnchor.TOP_RIGHT
        } else {
            priceCursor.labelAnchor = RectangleAnchor.TOP_RIGHT
            priceCursor.labelTextAnchor = TextAnchor.BOTTOM_RIGHT
        }
    }

    private fun installCursorMarkers() {
        log.debug(LogTag.UI, "installCursorMarkers()")
        val line = Color(74, 85, 96, 185)
        listOf(priceTimeCursor, volumeTimeCursor, priceCursor).forEach { marker ->
            marker.paint = line
            marker.stroke = BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(3f, 3f), 0f)
            marker.labelFont = Font("SansSerif", Font.BOLD, 12)
            marker.labelPaint = Color.WHITE
            marker.labelBackgroundColor = Color(51, 57, 63, 230)
            marker.labelOffset = RectangleInsets(5.0, 8.0, 5.0, 8.0)
        }
        priceCursor.labelAnchor = RectangleAnchor.TOP_RIGHT
        priceCursor.labelTextAnchor = TextAnchor.BOTTOM_RIGHT
        volumeTimeCursor.labelAnchor = RectangleAnchor.BOTTOM
        volumeTimeCursor.labelTextAnchor = TextAnchor.BOTTOM_CENTER
        pricePlot.addDomainMarker(priceTimeCursor)
        volumePlot.addDomainMarker(volumeTimeCursor)
        pricePlot.addRangeMarker(priceCursor)
        cursorMarkersInstalled = true
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

    private fun datePattern(bars: List<MinuteBar>): String {
        val span = bars.last().minuteEpochSeconds - bars.first().minuteEpochSeconds
        log.debug(LogTag.UI, "datePattern(spanSeconds={})", span)
        return when {
            span <= 86_400 -> "HH:mm"
            span <= 7 * 86_400 -> "dd MMM  HH:mm"
            span <= 180 * 86_400 -> "dd MMM"
            else -> "MMM yy"
        }
    }

    private companion object {
        const val MAX_CANDLES = 360
    }
}
