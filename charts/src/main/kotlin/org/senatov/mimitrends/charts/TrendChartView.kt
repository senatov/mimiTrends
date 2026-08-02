package org.senatov.mimitrends.charts

import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.geometry.Insets
import javafx.scene.input.MouseEvent
import org.jfree.chart.JFreeChart
import org.jfree.chart.axis.DateAxis
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.fx.ChartViewer
import org.jfree.chart.plot.CombinedDomainXYPlot
import org.jfree.chart.plot.ValueMarker
import org.jfree.chart.plot.IntervalMarker
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.CandlestickRenderer
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
import org.senatov.mimitrends.model.BrokerTrade
import org.senatov.mimitrends.model.ScanResult
import org.slf4j.LoggerFactory
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date

class TrendChartView : StackPane() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val dateAxis = DateAxis()
    private val priceAxis = NumberAxis()
    private val volumeAxis = NumberAxis("Volume")
    private val candleRenderer = CandlestickRenderer()
    private val volumeRenderer = DirectionalVolumeRenderer()
    private val pricePlot = XYPlot(null, null, priceAxis, candleRenderer)
    private val volumePlot = XYPlot(null, null, volumeAxis, volumeRenderer)
    private val combinedPlot = CombinedDomainXYPlot(dateAxis)
    private val chart = JFreeChart("", Font("SansSerif", Font.PLAIN, 14), combinedPlot, false)
    private val viewer = ChartViewer(chart)
    private val progress = ProgressIndicator()
    private val instrumentLabel = Label("Select a scanner result")
    private val currentPriceLabel = Label()
    private val chartDetailsLabel = Label("Price and volume history")
    private val signalSummaryLabel = Label()
    private val cursorDetailsLabel = Label("Move the cursor over a candle to inspect OHLC and volume")
    private val focusButton = ToggleButton("Signal focus")
    private val historyButton = ToggleButton("Full history")
    private val tradesButton = ToggleButton("Trades").apply { isSelected = true }
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
    private var lastRequest: RenderRequest? = null
    private var renderedBars: List<MinuteBar> = emptyList()
    private var renderedPriceMultiplier = 1.0
    private var renderedCurrencySymbol = "$"
    private val tradeAnnotations = BrokerTradeAnnotations(pricePlot)

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
        ToggleGroup().apply {
            focusButton.toggleGroup = this
            historyButton.toggleGroup = this
            selectToggle(focusButton)
        }
        listOf(focusButton, historyButton, tradesButton).forEach { it.styleClass += "chart-mode-button" }
        focusButton.setOnAction { lastRequest?.let(::renderRequest) }
        historyButton.setOnAction { lastRequest?.let(::renderRequest) }
        tradesButton.setOnAction { lastRequest?.let(::renderRequest) }
        val modeSwitch = HBox(focusButton, historyButton, tradesButton).apply { styleClass += "chart-mode-switch" }
        signalSummaryLabel.styleClass += "chart-signal-summary"
        cursorDetailsLabel.styleClass += "chart-cursor-details"
        currentPriceLabel.styleClass += "chart-current-price"
        val spacer = javafx.scene.layout.Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val identityRow = HBox(10.0, instrumentLabel, currentPriceLabel, chartDetailsLabel, spacer, modeSwitch).apply {
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }
        val header = VBox(2.0, identityRow, signalSummaryLabel, cursorDetailsLabel).apply {
            padding = Insets(7.0, 14.0, 7.0, 14.0)
            style = "-fx-background-color: rgba(248,250,253,0.96); -fx-border-color: transparent transparent #d7dde4 transparent;"
            instrumentLabel.style = "-fx-font-family: 'SF Pro Display'; -fx-font-size: 14px; -fx-font-weight: 500; -fx-text-fill: #1f3c59;"
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
        signal: ScanResult? = null,
        companyName: String = symbol,
        trades: List<BrokerTrade> = emptyList()
    ) {
        log.debug(LogTag.UI, "renderMinuteBars(symbol={}, bars={}, range={})", symbol, bars.size, rangeLabel)
        lastRequest = RenderRequest(symbol, companyName, bars.sortedBy { it.minuteEpochSeconds }, rangeLabel,
            priceMultiplier, currencySymbol, signal, trades)
        renderRequest(requireNotNull(lastRequest))
    }

    private fun renderRequest(request: RenderRequest) {
        val focused = focusButton.isSelected && request.signal != null
        val source = if (focused) focusOnSignal(request.bars, requireNotNull(request.signal)) else request.bars
        val visible = TrendChartSupport.aggregate(source, MAX_CANDLES)
        if (visible.isEmpty()) {
            clear()
            return
        }

        val dates = Array(visible.size) { Date(visible[it].minuteEpochSeconds * 1_000) }
        val highs = DoubleArray(visible.size) { visible[it].high * request.priceMultiplier }
        val lows = DoubleArray(visible.size) { visible[it].low * request.priceMultiplier }
        val opens = DoubleArray(visible.size) { visible[it].open * request.priceMultiplier }
        val closes = DoubleArray(visible.size) { visible[it].close * request.priceMultiplier }
        val volumes = DoubleArray(visible.size) { visible[it].volume }
        renderedBars = visible
        renderedPriceMultiplier = request.priceMultiplier
        renderedCurrencySymbol = request.currencySymbol
        volumeRenderer.directions = visible.map { bar ->
            when {
                bar.close > bar.open -> 1
                bar.close < bar.open -> -1
                else -> 0
            }
        }
        val signalEpoch = request.signal?.signalEpochMillis?.div(1_000L)
        volumeRenderer.signalColumn = signalEpoch?.let { epoch -> visible.indices.minByOrNull { kotlin.math.abs(visible[it].minuteEpochSeconds - epoch) } }
        pricePlot.dataset = DefaultHighLowDataset(request.symbol, dates, highs, lows, opens, closes, volumes)

        val volumeSeries = TimeSeries("Volume")
        visible.forEach { bar -> volumeSeries.addOrUpdate(Millisecond(Date(bar.minuteEpochSeconds * 1_000)), bar.volume) }
        val barWidthMillis = TrendChartSupport.inferBarWidth(visible)
        volumePlot.dataset = XYBarDataset(TimeSeriesCollection(volumeSeries), barWidthMillis)

        dateAxis.dateFormatOverride = SimpleDateFormat(TrendChartSupport.datePattern(visible))
        priceAxis.numberFormatOverride = DecimalFormat("${request.currencySymbol}#,##0.00")
        cursorDateFormat = SimpleDateFormat("dd MMM yyyy  HH:mm")
        cursorPriceFormat = DecimalFormat("${request.currencySymbol}#,##0.00")
        instrumentLabel.text = "${request.companyName}  (${request.symbol})"
        currentPriceLabel.text = "${request.currencySymbol}${"%,.2f".format(closes.last())} current"
        chartDetailsLabel.text = if (focused) "Signal focus · ${source.size} minute bars" else "${request.rangeLabel} · ${request.bars.size} minute bars"
        val signalBar = request.signal?.let { nearestSignalBar(request.bars, it) }
        signalSummaryLabel.text = request.signal?.let { signalSummary(it, signalBar, closes.last(), request) }.orEmpty()
        signalSummaryLabel.isVisible = request.signal != null
        signalSummaryLabel.isManaged = request.signal != null
        showLatestPrice(closes.last(), request.currencySymbol)
        showSignalWindow(request.bars.last().minuteEpochSeconds, request.signal)
        if (tradesButton.isSelected) tradeAnnotations.render(request.trades, visible, request.priceMultiplier)
        else tradeAnnotations.clear()
        chart.fireChartChanged()
    }

    private fun nearestSignalBar(bars: List<MinuteBar>, signal: ScanResult): MinuteBar? {
        if (bars.isEmpty()) return null
        val epoch = signal.signalEpochMillis / 1_000L
        return bars.minByOrNull { kotlin.math.abs(it.minuteEpochSeconds - epoch) }
    }

    private fun focusOnSignal(bars: List<MinuteBar>, signal: ScanResult): List<MinuteBar> {
        if (bars.isEmpty()) return bars
        val signalEpoch = signal.signalEpochMillis / 1_000L
        val signalIndex = bars.indices.minByOrNull { kotlin.math.abs(bars[it].minuteEpochSeconds - signalEpoch) } ?: bars.lastIndex
        val start = (signalIndex - SIGNAL_CONTEXT_MINUTES).coerceAtLeast(0)
        return bars.subList(start, bars.lastIndex + 1).takeLast(MAX_FOCUS_BARS)
    }

    private fun signalSummary(signal: ScanResult, signalBar: MinuteBar?, currentPrice: Double, request: RenderRequest): String {
        val age = if (signal.signalAgeMinutes == 0) "now" else "${signal.signalAgeMinutes}m ago"
        val date = signalBar?.let { SimpleDateFormat("dd MMM HH:mm").format(Date(it.minuteEpochSeconds * 1_000)) } ?: "—"
        val entry = signal.signalPrice.takeIf { it.isFinite() && it > 0.0 }?.times(request.priceMultiplier)
        val move = entry?.takeIf { it != 0.0 }?.let { (currentPrice - it) / it * 100.0 }
        val volume = if (signal.relativeVolume.isFinite()) " · RVOL %.1f×".format(signal.relativeVolume) else ""
        val prices = entry?.let {
            " · Entry ${request.currencySymbol}${"%,.2f".format(it)} → now ${request.currencySymbol}${"%,.2f".format(currentPrice)} (${move?.let { value -> "%+.2f%%".format(value) } ?: "—"})"
        }.orEmpty()
        return "${signal.signalSource.uppercase()} · $date · $age$prices · Score ${"%.2f".format(signal.anomalyScore)}×$volume"
    }

    fun setLoading(loading: Boolean) {
        log.debug(LogTag.UI, "setLoading(loading={})", loading)
        progress.isVisible = loading
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        pricePlot.dataset = null
        volumePlot.dataset = null
        if (latestPriceMarkerInstalled) {
            pricePlot.removeRangeMarker(latestPriceMarker)
            latestPriceMarkerInstalled = false
        }
        showSignalWindow(0, null)
        instrumentLabel.text = "No collected market data"
        currentPriceLabel.text = ""
        chartDetailsLabel.text = ""
        signalSummaryLabel.text = ""
        signalSummaryLabel.isVisible = false
        signalSummaryLabel.isManaged = false
        cursorDetailsLabel.text = "Move the cursor over a candle to inspect OHLC and volume"
        renderedBars = emptyList()
        tradeAnnotations.clear()
        chart.fireChartChanged()
    }

    private fun showSignalWindow(latestEpoch: Long, signal: ScanResult?) {
        log.debug(LogTag.UI, "showSignalWindow(latest={}, signal={})", latestEpoch, signal?.signalSource)
        priceSignalMarker?.let { pricePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        volumeSignalMarker?.let { volumePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        priceSignalMarker = null
        volumeSignalMarker = null
        if (signal == null || latestEpoch <= 0) return
        val isTrend = signal.signalSource.startsWith("Trend")
        val ageMinutes = signal.signalAgeMinutes
        val windowMinutes = if (isTrend) signal.signalWindowLabel.filter(Char::isDigit).toIntOrNull() ?: 180 else 1
        val end = signal.signalEpochMillis.toDouble()
        val start = end - windowMinutes * 60_000.0
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
        val signalBar = lastRequest?.let { nearestSignalBar(it.bars, signal) }
        val signalDate = signalBar?.let { SimpleDateFormat("dd MMM HH:mm").format(Date(it.minuteEpochSeconds * 1_000)) }
        val entry = lastRequest?.let { signal.signalPrice * it.priceMultiplier }
        val label = if (isTrend) "Trend ${signal.signalWindowLabel}" else "Signal"
        val numericLabel = listOfNotNull(label, signalDate, entry?.let { "Entry ${lastRequest?.currencySymbol}${"%,.2f".format(it)}" }).joinToString(" · ")
        priceSignalMarker = marker(numericLabel)
        volumeSignalMarker = marker(null)
        pricePlot.addDomainMarker(priceSignalMarker, Layer.BACKGROUND)
        volumePlot.addDomainMarker(volumeSignalMarker, Layer.BACKGROUND)
    }

    private fun showLatestPrice(value: Double, currencySymbol: String) {
        log.debug(LogTag.UI, "showLatestPrice(value={})", value)
        latestPriceMarker.value = value
        latestPriceMarker.label = "Current / exit  ${DecimalFormat("$currencySymbol#,##0.00").format(value)}"
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
        TrendChartSupport.configure(chart, viewer, dateAxis, priceAxis, volumeAxis, candleRenderer,
            volumeRenderer, pricePlot, volumePlot, combinedPlot, ::updateCursor)
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
        renderedBars.minByOrNull { kotlin.math.abs(it.minuteEpochSeconds * 1_000.0 - timeValue) }?.let { bar ->
            val format = { value: Double -> "$renderedCurrencySymbol${"%,.2f".format(value * renderedPriceMultiplier)}" }
            val change = if (bar.open != 0.0) (bar.close - bar.open) / bar.open * 100.0 else 0.0
            cursorDetailsLabel.text = "${cursorDateFormat.format(Date(bar.minuteEpochSeconds * 1_000))}  ·  O ${format(bar.open)}  H ${format(bar.high)}  L ${format(bar.low)}  C ${format(bar.close)}  ·  ${"%+.2f".format(change)}%  ·  Vol ${TrendChartSupport.compactVolume(bar.volume)}"
        }
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

    private data class RenderRequest(
        val symbol: String,
        val companyName: String,
        val bars: List<MinuteBar>,
        val rangeLabel: String,
        val priceMultiplier: Double,
        val currencySymbol: String,
        val signal: ScanResult?,
        val trades: List<BrokerTrade>
    )

    private companion object {
        const val MAX_CANDLES = 360
        const val SIGNAL_CONTEXT_MINUTES = 180
        const val MAX_FOCUS_BARS = 240
    }
}
