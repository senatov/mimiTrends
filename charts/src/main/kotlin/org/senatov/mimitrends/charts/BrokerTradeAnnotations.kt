package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.XYAnnotation
import org.jfree.chart.annotations.XYShapeAnnotation
import org.jfree.chart.annotations.XYTextAnnotation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.ui.TextAnchor
import org.senatov.mimitrends.model.BrokerTrade
import org.senatov.mimitrends.model.MinuteBar
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date

internal class BrokerTradeAnnotations(private val plot: XYPlot) {
    private val annotations = mutableListOf<XYAnnotation>()
    private val cardPositions = mutableMapOf<TradeKey, NormalizedPoint>()
    private val renderedCards = mutableListOf<RenderedCard>()
    private val renderedTradePoints = mutableListOf<TradePoint>()
    private var activeDrag: ActiveDrag? = null
    private var lastRender: RenderInput? = null

    fun render(
        trades: List<BrokerTrade>,
        bars: List<MinuteBar>,
        displayBars: List<MinuteBar>,
        barPriceMultiplier: Double,
        displayMillis: (Long) -> Double = { it * 1_000.0 }
    ) {
        lastRender = RenderInput(trades, bars, displayBars, barPriceMultiplier, displayMillis)
        renderLast()
    }

    private fun renderLast() {
        val input = lastRender ?: return
        val trades = input.trades
        val bars = input.bars
        val displayBars = input.displayBars
        val barPriceMultiplier = input.barPriceMultiplier
        val displayMillis = input.displayMillis
        clearAnnotations()
        if (bars.isEmpty()) return
        val firstEpoch = bars.first().minuteEpochSeconds
        val lastEpoch = bars.last().minuteEpochSeconds
        val visible = trades.filter { trade ->
            trade.entryEpochSeconds <= lastEpoch && (trade.exitEpochSeconds ?: lastEpoch) >= firstEpoch
        }
        val priceSpan = (bars.maxOf { it.high } - bars.minOf { it.low })
            .coerceAtLeast(bars.last().close * 0.02) * barPriceMultiplier
        val timeStep = medianBarSeconds(displayBars) * 1_000.0
        val domainMin = displayBars.first().minuteEpochSeconds * 1_000.0
        val domainMax = displayBars.last().minuteEpochSeconds * 1_000.0
        val rangeMin = bars.minOf { it.low } * barPriceMultiplier
        val candleRangeMax = bars.maxOf { it.high } * barPriceMultiplier
        val rangeMax = candleRangeMax + priceSpan * CARD_LANE_SHARE
        visible.forEach { trade ->
            val entryX = displayMillis(trade.entryEpochSeconds)
            val exitX = displayMillis(trade.exitEpochSeconds ?: lastEpoch)
            val entryY = trade.entryPrice
            val exitY = trade.exitPrice ?: trade.entryPrice
            val controlX = (entryX + exitX) / 2.0
            addTradeHighlight(trade, bars, entryX, exitX, timeStep, priceSpan, barPriceMultiplier)
            val entryAlignment = alignToCandle(trade.entryEpochSeconds,
                bars, timeStep, barPriceMultiplier, displayMillis)
            val exitAlignment = trade.exitEpochSeconds?.let { epoch ->
                alignToCandle(epoch, bars, timeStep, barPriceMultiplier, displayMillis)
            }
            val entryPoint = entryAlignment?.candlePoint ?: TradePoint(entryX, entryY)
            val exitPoint = exitAlignment?.candlePoint ?: TradePoint(exitX, exitY)
            addPoint(entryPoint.x, entryPoint.y, timeStep, priceSpan, ORANGE)
            addPoint(exitPoint.x, exitPoint.y, timeStep, priceSpan, if (trade.isOpen) ORANGE else pnlColor(trade))
            val key = TradeKey(trade.symbol, trade.entryEpochSeconds, trade.exitEpochSeconds)
            val stored = cardPositions[key]
            val preferredX = stored?.let { domainMin + (it.x * (domainMax - domainMin)) } ?: controlX
            val preferredBottom = stored?.let { rangeMin + (it.y * (rangeMax - rangeMin)) }
                ?: (candleRangeMax + priceSpan * CARD_GAP)
            val connectorPoints = listOfNotNull(
                entryAlignment?.candlePoint,
                exitAlignment?.candlePoint
            ).ifEmpty { listOf(entryPoint, exitPoint) }
            addCard(key, trade, connectorPoints, preferredX, preferredBottom, timeStep, priceSpan,
                domainMin, domainMax, rangeMin, rangeMax, entryAlignment, exitAlignment)
        }
    }

    fun beginDrag(domain: Double, range: Double): Boolean {
        activeDrag = renderedCards.asReversed().firstOrNull { it.bounds.contains(domain, range) }?.let { card ->
            ActiveDrag(card.key, domain - card.bounds.centerX, range - card.bounds.bottom)
        }
        return activeDrag != null
    }

    fun dragTo(domain: Double, range: Double) {
        val drag = activeDrag ?: return
        val card = renderedCards.firstOrNull { it.key == drag.key } ?: return
        val domainSpan = (card.domainMax - card.domainMin).coerceAtLeast(1.0)
        val rangeSpan = (card.rangeMax - card.rangeMin).coerceAtLeast(0.000_001)
        cardPositions[drag.key] = NormalizedPoint(
            ((domain - drag.domainOffset - card.domainMin) / domainSpan).coerceIn(0.0, 1.0),
            ((range - drag.rangeOffset - card.rangeMin) / rangeSpan).coerceIn(0.0, 1.0)
        )
        renderLast()
    }

    fun endDrag() {
        activeDrag = null
    }

    internal fun renderedCardBounds(): List<CardBounds> = renderedCards.map(RenderedCard::bounds)
    internal fun renderedTradePoints(): List<TradePoint> = renderedTradePoints.toList()

    private fun addTradeHighlight(
        trade: BrokerTrade,
        bars: List<MinuteBar>,
        entryX: Double,
        exitX: Double,
        timeStep: Double,
        priceSpan: Double,
        multiplier: Double
    ) {
        val exitEpoch = trade.exitEpochSeconds ?: bars.last().minuteEpochSeconds
        val covered = bars.filter { it.minuteEpochSeconds in trade.entryEpochSeconds..exitEpoch }
        val exitPrice = trade.exitPrice ?: trade.entryPrice
        val low = covered.minOfOrNull(MinuteBar::low)?.times(multiplier)
            ?: minOf(trade.entryPrice, exitPrice)
        val high = covered.maxOfOrNull(MinuteBar::high)?.times(multiplier)
            ?: maxOf(trade.entryPrice, exitPrice)
        val padding = priceSpan * HIGHLIGHT_PADDING
        val left = minOf(entryX, exitX) - timeStep * 0.38
        val right = maxOf(entryX, exitX) + timeStep * 0.38
        val bottom = low - padding
        val height = high - low + padding * 2.0
        add(RoundRectangle2D.Double(
            left, bottom, right - left, height, timeStep * 1.9, height * HIGHLIGHT_CORNER_SHARE
        ).let { XYShapeAnnotation(it, HIGHLIGHT_STROKE, HIGHLIGHT_ORANGE, HIGHLIGHT_FILL) })
    }

    fun clear() {
        lastRender = null
        activeDrag = null
        clearAnnotations()
    }

    private fun clearAnnotations() {
        annotations.forEach(plot::removeAnnotation)
        annotations.clear()
        renderedCards.clear()
        renderedTradePoints.clear()
    }

    private fun addPoint(x: Double, y: Double, timeStep: Double, priceSpan: Double, color: Color) {
        renderedTradePoints += TradePoint(x, y)
        val dot = Ellipse2D.Double(x - timeStep * 0.30, y - priceSpan * 0.010,
            timeStep * 0.60, priceSpan * 0.020)
        add(XYShapeAnnotation(dot, BasicStroke(1.6f), color.darker(), color))
    }

    private fun addCard(
        key: TradeKey,
        trade: BrokerTrade,
        connectorPoints: List<TradePoint>,
        x: Double,
        y: Double,
        timeStep: Double,
        priceSpan: Double,
        domainMin: Double,
        domainMax: Double,
        rangeMin: Double,
        rangeMax: Double,
        entryAlignment: CandleAlignment?,
        exitAlignment: CandleAlignment?
    ) {
        val formatter = DecimalFormat("#,##0.00")
        val symbol = currencySymbol(trade.currency)
        val entry = formatter.format(trade.entryPrice)
        val exit = trade.exitPrice?.let(formatter::format)
        val title = if (exit == null) "BUY $symbol$entry · OPEN"
        else "BUY $symbol$entry → SELL $symbol$exit"
        val aligned = listOfNotNull(entryAlignment, exitAlignment).firstOrNull()
        val sessionNote = aligned?.let {
            " · extended → candle ${SimpleDateFormat("HH:mm").format(Date(it.candleX.toLong()))}"
        }.orEmpty()
        val pnl = trade.profitAmount?.let { amount ->
            val sign = if (amount >= 0.0) "+" else "−"
            val absolute = formatter.format(kotlin.math.abs(amount))
            val percent = trade.profitPercent?.let { " · $sign${formatter.format(kotlin.math.abs(it))}%" }.orEmpty()
            "$sign$symbol$absolute$percent"
        } ?: "Open position · ${formatter.format(trade.quantity)} shares"
        val bounds = TradeCardLayout.place(
            x, y, timeStep, priceSpan, domainMin, domainMax, rangeMin, rangeMax,
            renderedCards.map(RenderedCard::bounds)
        )
        renderedCards += RenderedCard(key, bounds, domainMin, domainMax, rangeMin, rangeMax)
        addCardConnector(connectorPoints, bounds, timeStep, priceSpan)
        val cardShape = RoundRectangle2D.Double(
            bounds.left,
            bounds.bottom,
            bounds.width,
            bounds.height,
            bounds.width * CARD_CORNER_WIDTH_SHARE,
            bounds.height * CARD_CORNER_HEIGHT_SHARE
        )
        add(XYShapeAnnotation(cardShape, CARD_STROKE, CARD_BORDER, CARD_FILL))
        add(text(title + sessionNote, bounds.centerX, bounds.bottom + bounds.height * 0.68,
            Color(48, 55, 63), Font.PLAIN))
        add(text(pnl, bounds.centerX, bounds.bottom + bounds.height * 0.30, pnlColor(trade), Font.BOLD))
    }

    private fun addCardConnector(
        points: List<TradePoint>,
        card: CardBounds,
        timeStep: Double,
        priceSpan: Double
    ) {
        val anchor = connectorAnchor(points, card, timeStep, priceSpan)
        TradeCardConnector.create(
            TradeCardConnector.Bounds(anchor.x, anchor.y, anchor.x, anchor.y),
            TradeCardConnector.Bounds(card.left, card.bottom, card.right, card.top),
            timeStep,
            priceSpan
        ).forEach(::add)
    }

    internal fun connectorAnchor(
        points: List<TradePoint>,
        card: CardBounds,
        timeStep: Double,
        priceSpan: Double
    ): TradePoint = points.minBy { point ->
            val dx = (point.x - card.centerX) / timeStep.coerceAtLeast(1.0)
            val dy = (point.y - card.centerY) / priceSpan.coerceAtLeast(0.000_001)
            dx * dx + dy * dy
        }

    internal fun cardBounds(
        preferredX: Double,
        preferredBottom: Double,
        timeStep: Double,
        priceSpan: Double,
        domainMin: Double,
        domainMax: Double,
        rangeMin: Double,
        rangeMax: Double
    ): CardBounds = TradeCardLayout.bounds(
        preferredX, preferredBottom, timeStep, priceSpan, domainMin, domainMax, rangeMin, rangeMax
    )

    private fun alignToCandle(
        epochSeconds: Long,
        bars: List<MinuteBar>,
        timeStep: Double,
        multiplier: Double,
        displayMillis: (Long) -> Double
    ): CandleAlignment? {
        val nearest = bars.minByOrNull { kotlin.math.abs(it.minuteEpochSeconds - epochSeconds) } ?: return null
        val candleX = displayMillis(nearest.minuteEpochSeconds)
        if (kotlin.math.abs(nearest.minuteEpochSeconds - epochSeconds) <= timeStep / 1_000.0 * 1.5) return null
        return CandleAlignment(candleX, nearest.close * multiplier)
    }

    private fun add(annotation: XYAnnotation) {
        annotations += annotation
        plot.addAnnotation(annotation)
    }

    private fun text(value: String, x: Double, y: Double, color: Color, style: Int) =
        XYTextAnnotation(value, x, y).apply {
            font = Font("SansSerif", style, 12)
            paint = color
            textAnchor = TextAnchor.CENTER
        }

    private fun pnlColor(trade: BrokerTrade): Color = when {
        trade.profitAmount == null -> ORANGE
        requireNotNull(trade.profitAmount) >= 0.0 -> Color(22, 137, 76)
        else -> Color(194, 48, 62)
    }

    private fun medianBarSeconds(bars: List<MinuteBar>): Double {
        if (bars.size < 2) return 60.0
        val intervals = bars.zipWithNext { first, second ->
            (second.minuteEpochSeconds - first.minuteEpochSeconds).coerceAtLeast(1).toDouble()
        }.sorted()
        return intervals[intervals.size / 2]
    }

    private fun currencySymbol(currency: String) = when (currency.uppercase()) {
        "EUR" -> "€"
        "GBP" -> "£"
        else -> "$"
    }

    private companion object {
        val ORANGE = Color(235, 133, 35, 225)
        val HIGHLIGHT_ORANGE = Color(226, 122, 25, 190)
        val HIGHLIGHT_FILL = Color(255, 180, 52, 52)
        val HIGHLIGHT_STROKE = BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val CARD_BORDER = Color(91, 72, 126, 215)
        val CARD_FILL = Color(248, 250, 252, 235)
        val CARD_STROKE = BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        const val HIGHLIGHT_PADDING = 0.035
        const val HIGHLIGHT_CORNER_SHARE = 0.72
        const val CARD_GAP = 0.035
        const val CARD_LANE_SHARE = 0.24
        const val CARD_CORNER_WIDTH_SHARE = 0.08
        const val CARD_CORNER_HEIGHT_SHARE = 0.72
    }

    private data class CandleAlignment(
        val candleX: Double,
        val candleY: Double
    ) {
        val candlePoint: TradePoint get() = TradePoint(candleX, candleY)
    }

    internal data class CardBounds(val left: Double, val bottom: Double, val right: Double, val top: Double) {
        val centerX: Double get() = (left + right) / 2.0
        val centerY: Double get() = (bottom + top) / 2.0
        val width: Double get() = right - left
        val height: Double get() = top - bottom
        fun contains(x: Double, y: Double): Boolean = x in left..right && y in bottom..top
    }

    internal data class TradePoint(val x: Double, val y: Double)

    private data class TradeKey(val symbol: String, val entryEpoch: Long, val exitEpoch: Long?)
    private data class ActiveDrag(val key: TradeKey, val domainOffset: Double, val rangeOffset: Double)
    private data class NormalizedPoint(val x: Double, val y: Double)
    private data class RenderedCard(
        val key: TradeKey,
        val bounds: CardBounds,
        val domainMin: Double,
        val domainMax: Double,
        val rangeMin: Double,
        val rangeMax: Double
    )
    private data class RenderInput(
        val trades: List<BrokerTrade>,
        val bars: List<MinuteBar>,
        val displayBars: List<MinuteBar>,
        val barPriceMultiplier: Double,
        val displayMillis: (Long) -> Double
    )
}
