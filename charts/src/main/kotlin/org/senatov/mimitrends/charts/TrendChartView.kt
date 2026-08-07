package org.senatov.mimitrends.charts

import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.geometry.Insets
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
    private val cursorDetailsLabel = Label("Move anywhere above a candle to inspect it · click to pin")
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
    private val latestPriceMarker = LatestPriceMarkerController(pricePlot)
    private var lastRequest: TrendChartRenderRequest? = null
    private var renderedBars: List<MinuteBar> = emptyList()
    private var renderedTimeline = ChartTimeline.linear(emptyList())
    private var renderedPriceMultiplier = 1.0
    private var renderedCurrencySymbol = "$"
    private var cursorPinned = false
    private val tradeAnnotations = BrokerTradeAnnotations(pricePlot)
    @Suppress("unused")
    private val tradeAnnotationDragController = TradeAnnotationDragController(
        viewer, dateAxis, priceAxis, pricePlot, tradeAnnotations
    )
    private val signalTrendOverlay = SignalTrendOverlay(pricePlot)
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
        val viewModes = ToggleGroup().apply {
            focusButton.toggleGroup = this
            historyButton.toggleGroup = this
            selectToggle(focusButton)
        }
        focusButton.styleClass += listOf("chart-mode-button", "chart-view-button")
        historyButton.styleClass += listOf("chart-mode-button", "chart-view-button")
        tradesButton.styleClass += listOf("chart-mode-button", "chart-overlay-button")
        focusButton.tooltip = Tooltip("Show detailed candles around the selected signal")
        historyButton.tooltip = Tooltip("Show the complete loaded chart range")
        tradesButton.tooltip = Tooltip("Show or hide executed broker trades")
        focusButton.setOnAction {
            if (viewModes.selectedToggle == null) focusButton.isSelected = true
            lastRequest?.let(::renderRequest)
        }
        historyButton.setOnAction {
            if (viewModes.selectedToggle == null) historyButton.isSelected = true
            lastRequest?.let(::renderRequest)
        }
        tradesButton.setOnAction { lastRequest?.let(::renderRequest) }
        val viewSwitch = HBox(focusButton, historyButton).apply { styleClass += "chart-mode-switch" }
        val overlaySwitch = HBox(tradesButton).apply { styleClass += listOf("chart-mode-switch", "chart-overlay-switch") }
        val modeSwitch = HBox(8.0,
            VBox(1.0, Label("VIEW").apply { styleClass += "chart-mode-caption" }, viewSwitch),
            VBox(1.0, Label("OVERLAY").apply { styleClass += "chart-mode-caption" }, overlaySwitch)
        ).apply { alignment = javafx.geometry.Pos.BOTTOM_LEFT }
        signalSummaryLabel.styleClass += "chart-signal-summary"
        cursorDetailsLabel.styleClass += "chart-cursor-details"
        cursorDetailsLabel.isWrapText = true
        cursorDetailsLabel.maxWidth = Double.MAX_VALUE
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
        lastRequest = TrendChartRenderRequest(symbol, companyName, bars.sortedBy { it.minuteEpochSeconds }, rangeLabel,
            priceMultiplier, currencySymbol, signal, trades)
        renderRequest(requireNotNull(lastRequest))
    }
    private fun renderRequest(request: TrendChartRenderRequest) {
        val focused = focusButton.isSelected && request.signal != null
        val tradeEpochs = if (tradesButton.isSelected) request.trades.flatMap { trade ->
            listOfNotNull(trade.entryEpochSeconds, trade.exitEpochSeconds)
        } else emptyList()
        val timeline = if (focused) ChartTimeline.focused(
            request.bars, requireNotNull(request.signal).signalEpochMillis / 1_000L, tradeEpochs
        )
        else ChartTimeline.linear(TrendChartSupport.aggregate(request.bars, MAX_CANDLES))
        val visible = timeline.actualBars
        val plotted = timeline.plottedBars
        if (visible.isEmpty()) {
            clear()
            return
        }
        val dates = Array(plotted.size) { Date(plotted[it].minuteEpochSeconds * 1_000) }
        val highs = DoubleArray(visible.size) { visible[it].high * request.priceMultiplier }
        val lows = DoubleArray(visible.size) { visible[it].low * request.priceMultiplier }
        val opens = DoubleArray(visible.size) { visible[it].open * request.priceMultiplier }
        val closes = DoubleArray(visible.size) { visible[it].close * request.priceMultiplier }
        val volumes = DoubleArray(visible.size) { visible[it].volume }
        renderedBars = visible
        renderedTimeline = timeline
        cursorPinned = false
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
        visible.indices.forEach { index ->
            volumeSeries.addOrUpdate(Millisecond(Date(plotted[index].minuteEpochSeconds * 1_000)), visible[index].volume)
        }
        val barWidthMillis = TrendChartSupport.inferBarWidth(plotted)
        volumePlot.dataset = XYBarDataset(TimeSeriesCollection(volumeSeries), barWidthMillis)

        dateAxis.dateFormatOverride = timeline.dateFormat(TrendChartSupport.datePattern(visible))
        priceAxis.numberFormatOverride = DecimalFormat("${request.currencySymbol}#,##0.00")
        cursorDateFormat = SimpleDateFormat("dd MMM yyyy  HH:mm")
        cursorPriceFormat = DecimalFormat("${request.currencySymbol}#,##0.00")
        instrumentLabel.text = "${request.companyName}  (${request.symbol})"
        currentPriceLabel.text = "${request.currencySymbol}${"%,.2f".format(closes.last())} current"
        chartDetailsLabel.text = if (focused) "Signal focus · expanded recent impulse · ${visible.size} candles"
            else "${request.rangeLabel} · ${request.bars.size} minute bars"
        val signalBar = request.signal?.let { SignalChartPresentation.nearestBar(request.bars, it) }
        signalSummaryLabel.text = request.signal?.let {
            SignalChartPresentation.summary(it, signalBar, closes.last(), request.priceMultiplier, request.currencySymbol)
        }.orEmpty()
        signalSummaryLabel.isVisible = request.signal != null
        signalSummaryLabel.isManaged = request.signal != null
        log.debug(LogTag.UI, "showLatestPrice(value={})", closes.last())
        latestPriceMarker.show(closes.last(), request.currencySymbol)
        showSignalWindow(request.bars.last().minuteEpochSeconds, request.signal, timeline)
        if (focused) signalTrendOverlay.render(timeline, request.priceMultiplier) else signalTrendOverlay.clear()
        if (tradesButton.isSelected) tradeAnnotations.render(request.trades, visible, plotted,
            request.priceMultiplier, timeline::displayMillis)
        else tradeAnnotations.clear()
        priceAxis.upperMargin = if (tradesButton.isSelected && request.trades.isNotEmpty()) 0.28 else 0.04
        refreshAxisRanges()
        chart.fireChartChanged()
    }

    private fun refreshAxisRanges() {
        listOf(dateAxis, priceAxis, volumeAxis).forEach { axis ->
            axis.isAutoRange = false
            axis.isAutoRange = true
        }
    }

    fun setLoading(loading: Boolean) {
        log.debug(LogTag.UI, "setLoading(loading={})", loading)
        progress.isVisible = loading
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        pricePlot.dataset = null
        volumePlot.dataset = null
        latestPriceMarker.clear()
        showSignalWindow(0, null, renderedTimeline)
        instrumentLabel.text = "No collected market data"
        currentPriceLabel.text = ""
        chartDetailsLabel.text = ""
        signalSummaryLabel.text = ""
        signalSummaryLabel.isVisible = false
        signalSummaryLabel.isManaged = false
        cursorDetailsLabel.text = "Move anywhere above a candle to inspect it · click to pin"
        cursorPinned = false
        renderedBars = emptyList()
        renderedTimeline = ChartTimeline.linear(emptyList())
        tradeAnnotations.clear()
        signalTrendOverlay.clear()
        chart.fireChartChanged()
    }

    private fun showSignalWindow(latestEpoch: Long, signal: ScanResult?, timeline: ChartTimeline) {
        log.debug(LogTag.UI, "showSignalWindow(latest={}, signal={})", latestEpoch, signal?.signalSource)
        priceSignalMarker?.let { pricePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        volumeSignalMarker?.let { volumePlot.removeDomainMarker(it, Layer.BACKGROUND) }
        priceSignalMarker = null
        volumeSignalMarker = null
        if (signal == null || latestEpoch <= 0) return
        val isTrend = signal.signalSource.startsWith("Trend") || signal.signalSource.startsWith("Steady rise") ||
            signal.signalSource.startsWith("Recovery")
        val isMomentum = signal.signalSource.startsWith("Momentum")
        val isReversal = signal.signalSource.startsWith("V-Reversal")
        val ageMinutes = signal.signalAgeMinutes
        val windowMinutes = when {
            isTrend -> signal.signalWindowLabel.filter(Char::isDigit).toIntOrNull() ?: 180
            isMomentum -> 3
            isReversal -> signal.signalWindowLabel.filter(Char::isDigit).toIntOrNull() ?: 5
            else -> 1
        }
        val endEpoch = signal.signalEpochMillis / 1_000L
        val end = timeline.displayMillis(endEpoch)
        val start = timeline.displayMillis(endEpoch - windowMinutes * 60L)
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
        val signalBar = lastRequest?.let { SignalChartPresentation.nearestBar(it.bars, signal) }
        val signalDate = signalBar?.let { SimpleDateFormat("dd MMM HH:mm").format(Date(it.minuteEpochSeconds * 1_000)) }
        val entry = lastRequest?.let { signal.signalPrice * it.priceMultiplier }
        val label = when {
            isTrend -> "Trend ${signal.signalWindowLabel}"
            isMomentum -> "Early momentum 3m"
            isReversal -> "V-reversal ${signal.signalWindowLabel}"
            else -> "Signal"
        }
        val numericLabel = listOfNotNull(label, signalDate, entry?.let { "Entry ${lastRequest?.currencySymbol}${"%,.2f".format(it)}" }).joinToString(" · ")
        priceSignalMarker = marker(numericLabel)
        volumeSignalMarker = marker(null)
        pricePlot.addDomainMarker(priceSignalMarker, Layer.BACKGROUND)
        volumePlot.addDomainMarker(volumeSignalMarker, Layer.BACKGROUND)
    }

    private fun configureChart() {
        log.debug(LogTag.UI, "configureChart()")
        TrendChartSupport.configure(chart, viewer, dateAxis, priceAxis, volumeAxis, candleRenderer,
            volumeRenderer, pricePlot, volumePlot, combinedPlot, ::updateCursor, ::togglePinnedCursor)
    }

    private fun updateCursor(x: Double, y: Double) {
        log.trace(LogTag.UI, "updateCursor(x={}, y={})", x, y)
        if (cursorPinned) return
        showCursorAt(x, y)
    }

    private fun togglePinnedCursor(x: Double, y: Double) {
        if (cursorPinned) {
            cursorPinned = false
            cursorDetailsLabel.text = cursorDetailsLabel.text.substringBefore("  ·  PINNED") + "  ·  LIVE"
            return
        }
        if (showCursorAt(x, y)) {
            cursorPinned = true
            cursorDetailsLabel.text = cursorDetailsLabel.text.substringBefore("  ·  LIVE") + "  ·  PINNED"
        }
    }

    private fun showCursorAt(x: Double, y: Double): Boolean {
        val plotInfo = viewer.canvas.renderingInfo.plotInfo
        if (plotInfo.subplotCount < 2 || renderedBars.isEmpty()) return false
        val priceArea = plotInfo.getSubplotInfo(0).dataArea
        val volumeArea = plotInfo.getSubplotInfo(1).dataArea
        if (x !in priceArea.minX..priceArea.maxX || y !in priceArea.minY..volumeArea.maxY) return false
        val timeValue = dateAxis.java2DToValue(x, priceArea, RectangleEdge.BOTTOM)
        if (!timeValue.isFinite()) return false
        val bar = renderedTimeline.actualBarAt(timeValue) ?: return false
        val snappedTime = renderedTimeline.displayMillis(bar.minuteEpochSeconds)
        val priceValue = if (priceArea.contains(x, y)) priceAxis.java2DToValue(y, priceArea, pricePlot.rangeAxisEdge)
        else bar.close * renderedPriceMultiplier
        if (!priceValue.isFinite()) return false
        if (!cursorMarkersInstalled) installCursorMarkers()
        priceTimeCursor.value = snappedTime
        volumeTimeCursor.value = snappedTime
        priceCursor.value = priceValue
        volumeTimeCursor.label = cursorDateFormat.format(Date(bar.minuteEpochSeconds * 1_000L))
        priceCursor.label = cursorPriceFormat.format(priceValue)
        showBarDetails(bar)
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
        return true
    }

    private fun showBarDetails(bar: MinuteBar) {
        cursorDetailsLabel.text = CandleInspectorPresentation.text(
            bar, renderedPriceMultiplier, renderedCurrencySymbol, cursorDateFormat
        )
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

    private companion object {
        const val MAX_CANDLES = 360
    }
}
