package org.senatov.mimitrends.charts

import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.layout.Priority
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
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
private const val MAX_CANDLES = 360

class TrendChartView(
    private val onRangeChanged: (String) -> Unit = {},
    private val onRetry: () -> Unit = {}
) : StackPane() {
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
    private val stateOverlay = ChartStateOverlay()
    private val header = TrendChartHeader(
        { lastRequest?.let(::renderRequest) },
        { lastRequest?.let(::renderRequest) },
        onRangeChanged
    )
    private val priceCursor = ValueMarker(0.0)
    private val priceTimeCursor = ValueMarker(0.0)
    private val volumeTimeCursor = ValueMarker(0.0)
    private val timeCursorLabel = ChartCursorLabelAnnotation(
        ChartCursorLabelAnnotation.Placement.DOMAIN_BOTTOM
    )
    private val priceCursorLabel = ChartCursorLabelAnnotation(
        ChartCursorLabelAnnotation.Placement.RANGE_RIGHT
    )
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
    private var requestedFocusEpochSeconds: Long? = null
    private val tradeAnnotations = BrokerTradeAnnotations(pricePlot)
    @Suppress("unused")
    private val tradeAnnotationDragController = TradeAnnotationDragController(
        viewer, dateAxis, priceAxis, pricePlot, tradeAnnotations
    )
    private val signalTrendOverlay = SignalTrendOverlay(pricePlot)
    init {
        log.debug(LogTag.UI, "init()")
        styleClass += "trend-chart-card"
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
        configureChart()
        viewer.minWidth = 0.0
        viewer.minHeight = 0.0
        viewer.maxWidth = Double.MAX_VALUE
        viewer.maxHeight = Double.MAX_VALUE
        viewer.isFocusTraversable = true
        viewer.addEventHandler(MouseEvent.MOUSE_PRESSED) { viewer.requestFocus() }
        viewer.addEventHandler(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ESCAPE && cursorPinned) {
                releasePinnedCursor()
                event.consume()
            }
        }
        val viewerShell = StackPane(viewer, stateOverlay).apply { styleClass += "chart-viewer-shell" }
        val content = VBox(header, viewerShell).apply { styleClass += "chart-card-content" }
        VBox.setVgrow(viewerShell, Priority.ALWAYS)
        children += content
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

    fun showSignalFocus(epochSeconds: Long? = null) {
        requestedFocusEpochSeconds = epochSeconds
        header.selectFocus()
    }

    fun showFullHistory() {
        header.selectHistory()
        lastRequest?.let(::renderRequest)
    }

    fun prepareForInstrument(symbol: String) {
        if (lastRequest?.symbol == symbol) return
        clear()
        header.showLoading(symbol)
    }

    private fun renderRequest(request: TrendChartRenderRequest) {
        // A failed or interrupted render must never leave cards from the previously selected instrument.
        tradeAnnotations.clear()
        val focusEpoch = requestedFocusEpochSeconds ?: request.signal?.signalEpochMillis?.div(1_000L)
        val focused = header.focused && focusEpoch != null
        val timeline = if (focused) ChartTimeline.focused(request.bars, requireNotNull(focusEpoch), request.tradeEpochSeconds)
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
        val details = if (focused) "Signal focus · expanded recent impulse · ${visible.size} candles"
            else "${request.rangeLabel} · ${request.bars.size} minute bars"
        val signalBar = request.signal?.let { SignalChartPresentation.nearestBar(request.bars, it) }
        val signalSummary = request.signal?.let {
            SignalChartPresentation.summary(it, signalBar, closes.last(), request.priceMultiplier, request.currencySymbol)
        }
        header.selectRange(request.rangeLabel)
        header.showInstrument(request.companyName, request.symbol,
            "${request.currencySymbol}${"%,.2f".format(closes.last())}", details, signalSummary)
        log.debug(LogTag.UI, "showLatestPrice(value={})", closes.last())
        latestPriceMarker.show(closes.last(), request.currencySymbol)
        showSignalWindow(request.bars.last().minuteEpochSeconds, request.signal, timeline)
        if (focused) signalTrendOverlay.render(timeline, request.priceMultiplier) else signalTrendOverlay.clear()
        if (header.tradesVisible) tradeAnnotations.render(request.matchingTrades, visible, plotted,
            request.priceMultiplier, timeline::displayMillis)
        else tradeAnnotations.clear()
        priceAxis.upperMargin = if (header.tradesVisible && request.matchingTrades.isNotEmpty()) 0.28 else 0.04
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
        stateOverlay.showLoading(loading)
    }

    fun showEmpty(symbol: String, range: String) {
        clear()
        header.showUnavailable(symbol, "No collected minute bars · $range")
        showState("No chart data for $symbol", "Try a wider range or wait for the next collection cycle.", "chart-state-empty")
        stateOverlay.showAction(if (range == "1Y") "Try again" else "Show 1Y") {
            if (range == "1Y") onRetry() else onRangeChanged("1Y")
        }
    }

    fun showError(symbol: String) {
        clear()
        header.showUnavailable(symbol, "Chart data could not be loaded")
        showState(
            "Unable to load $symbol",
            "Use Refresh to try again. Diagnostic details are available in the status bar.",
            "chart-state-error"
        )
        stateOverlay.showAction("Try again", onRetry)
    }

    private fun showState(title: String, detail: String, styleClass: String) {
        stateOverlay.showMessage(title, detail, styleClass)
    }

    fun setDarkTheme(dark: Boolean) {
        TrendChartSupport.applyTheme(dark, chart, dateAxis, priceAxis, volumeAxis, pricePlot, volumePlot, combinedPlot)
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        lastRequest = null
        requestedFocusEpochSeconds = null
        pricePlot.dataset = null
        volumePlot.dataset = null
        latestPriceMarker.clear()
        showSignalWindow(0, null, renderedTimeline)
        header.clear()
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
            releasePinnedCursor()
            return
        }
        if (showCursorAt(x, y)) {
            cursorPinned = true
            header.cursorText = header.cursorText.substringBefore("  ·  LIVE") + "  ·  PINNED"
        }
    }

    private fun releasePinnedCursor() {
        cursorPinned = false
        header.cursorText = header.cursorText.substringBefore("  ·  PINNED") + "  ·  LIVE"
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
        timeCursorLabel.value = snappedTime
        timeCursorLabel.text = cursorDateFormat.format(Date(bar.minuteEpochSeconds * 1_000L))
        priceCursorLabel.value = priceValue
        priceCursorLabel.text = cursorPriceFormat.format(priceValue)
        showBarDetails(bar)
        if (y < priceArea.centerY) {
            priceCursorLabel.placeBeforeLine = false
        } else {
            priceCursorLabel.placeBeforeLine = true
        }
        return true
    }

    private fun showBarDetails(bar: MinuteBar) {
        header.cursorText = CandleInspectorPresentation.text(
            bar, renderedPriceMultiplier, renderedCurrencySymbol, cursorDateFormat
        )
    }

    private fun installCursorMarkers() {
        log.debug(LogTag.UI, "installCursorMarkers()")
        val line = Color(74, 85, 96, 185)
        listOf(priceTimeCursor, volumeTimeCursor, priceCursor).forEach { marker ->
            marker.paint = line
            marker.stroke = BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(3f, 3f), 0f)
        }
        pricePlot.addDomainMarker(priceTimeCursor)
        volumePlot.addDomainMarker(volumeTimeCursor)
        pricePlot.addRangeMarker(priceCursor)
        pricePlot.addAnnotation(priceCursorLabel)
        volumePlot.addAnnotation(timeCursorLabel)
        cursorMarkersInstalled = true
    }
}
