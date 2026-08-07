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
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date

internal class BrokerTradeAnnotations(private val plot: XYPlot) {
    private val annotations = mutableListOf<XYAnnotation>()
    private val cardPositions = mutableMapOf<TradeKey, NormalizedPoint>()
    private val renderedCards = mutableListOf<RenderedCard>()
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
        visible.forEachIndexed { index, trade ->
            val entryX = displayMillis(trade.entryEpochSeconds)
            val exitX = displayMillis(trade.exitEpochSeconds ?: lastEpoch)
            val entryY = trade.entryPrice * barPriceMultiplier
            val exitY = (trade.exitPrice ?: trade.entryPrice) * barPriceMultiplier
            val controlX = (entryX + exitX) / 2.0
            val highlight = addTradeHighlight(trade, bars, entryX, exitX, timeStep, priceSpan,
                barPriceMultiplier)
            val entryAlignment = alignToCandle(trade.entryEpochSeconds, trade.entryPrice,
                bars, timeStep, barPriceMultiplier, displayMillis)
            val exitAlignment = trade.exitEpochSeconds?.let { epoch ->
                alignToCandle(epoch, requireNotNull(trade.exitPrice), bars, timeStep, barPriceMultiplier, displayMillis)
            }
            entryAlignment?.let(::addConnector)
            exitAlignment?.let(::addConnector)
            addPoint(entryX, entryY, timeStep, priceSpan, ORANGE)
            addPoint(exitX, exitY, timeStep, priceSpan, if (trade.isOpen) ORANGE else pnlColor(trade))
            val key = TradeKey(trade.symbol, trade.entryEpochSeconds, trade.exitEpochSeconds)
            val stored = cardPositions[key]
            val preferredX = stored?.let { domainMin + (it.x * (domainMax - domainMin)) } ?: controlX
            val preferredBottom = stored?.let { rangeMin + (it.y * (rangeMax - rangeMin)) }
                ?: (candleRangeMax + (priceSpan * (CARD_GAP + ((index % MAX_LEVELS) * CARD_LEVEL_GAP))))
            addCard(key, trade, highlight, preferredX, preferredBottom, timeStep, priceSpan,
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

    private fun addTradeHighlight(
        trade: BrokerTrade,
        bars: List<MinuteBar>,
        entryX: Double,
        exitX: Double,
        timeStep: Double,
        priceSpan: Double,
        multiplier: Double
    ): HighlightAnchor {
        val exitEpoch = trade.exitEpochSeconds ?: bars.last().minuteEpochSeconds
        val covered = bars.filter { it.minuteEpochSeconds in trade.entryEpochSeconds..exitEpoch }
        val exitPrice = trade.exitPrice ?: trade.entryPrice
        val low = (covered.minOfOrNull(MinuteBar::low) ?: minOf(trade.entryPrice, exitPrice)) * multiplier
        val high = (covered.maxOfOrNull(MinuteBar::high) ?: maxOf(trade.entryPrice, exitPrice)) * multiplier
        val padding = priceSpan * HIGHLIGHT_PADDING
        val left = minOf(entryX, exitX) - timeStep * 0.38
        val right = maxOf(entryX, exitX) + timeStep * 0.38
        val bottom = low - padding
        val height = high - low + padding * 2.0
        add(RoundRectangle2D.Double(
            left, bottom, right - left, height, timeStep * 1.6, priceSpan * 0.09
        ).let { XYShapeAnnotation(it, HIGHLIGHT_STROKE, HIGHLIGHT_ORANGE, HIGHLIGHT_FILL) })
        return HighlightAnchor((left + right) / 2.0, bottom, bottom + height)
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
    }

    private fun addPoint(x: Double, y: Double, timeStep: Double, priceSpan: Double, color: Color) {
        val dot = Ellipse2D.Double(x - timeStep * 0.30, y - priceSpan * 0.010,
            timeStep * 0.60, priceSpan * 0.020)
        add(XYShapeAnnotation(dot, BasicStroke(1.6f), color.darker(), color))
    }

    private fun addCard(
        key: TradeKey,
        trade: BrokerTrade,
        highlight: HighlightAnchor,
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
        val bounds = cardBounds(x, y, timeStep, priceSpan, domainMin, domainMax, rangeMin, rangeMax)
        renderedCards += RenderedCard(key, bounds, domainMin, domainMax, rangeMin, rangeMax)
        addCardConnector(highlight, bounds, timeStep)
        val cardShape = RoundRectangle2D.Double(
            bounds.left,
            bounds.bottom,
            bounds.width,
            bounds.height,
            timeStep * CARD_CORNER_BARS,
            bounds.height * CARD_CORNER_HEIGHT_SHARE
        )
        add(XYShapeAnnotation(cardShape, CARD_STROKE, CARD_BORDER, CARD_FILL))
        add(text(title + sessionNote, bounds.centerX, bounds.bottom + bounds.height * 0.68,
            Color(48, 55, 63), Font.PLAIN))
        add(text(pnl, bounds.centerX, bounds.bottom + bounds.height * 0.30, pnlColor(trade), Font.BOLD))
    }

    private fun addCardConnector(highlight: HighlightAnchor, card: CardBounds, timeStep: Double) {
        val cardIsAbove = card.bottom >= highlight.top
        val startY = if (cardIsAbove) highlight.top else highlight.bottom
        val endY = if (cardIsAbove) card.bottom else card.top
        val endX = card.centerX
        val direction = if (cardIsAbove) 1.0 else -1.0
        val bend = (endX - highlight.centerX).coerceIn(-timeStep * 3.0, timeStep * 3.0)
        val verticalDistance = kotlin.math.abs(endY - startY)
        val path = Path2D.Double().apply {
            moveTo(highlight.centerX, startY)
            curveTo(
                highlight.centerX + bend * 0.15,
                startY + direction * verticalDistance * 0.45,
                endX - bend * 0.25,
                endY - direction * verticalDistance * 0.35,
                endX,
                endY
            )
        }
        add(XYShapeAnnotation(path, CARD_CONNECTOR_STROKE, CARD_CONNECTOR))
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
    ): CardBounds {
        val domainSpan = (domainMax - domainMin).coerceAtLeast(timeStep)
        val width = minOf(timeStep * CARD_WIDTH_BARS, domainSpan * MAX_CARD_DOMAIN_SHARE)
        val height = minOf(priceSpan * CARD_HEIGHT_SHARE, (rangeMax - rangeMin) * MAX_CARD_RANGE_SHARE)
        val horizontalPadding = minOf(timeStep * CARD_EDGE_PADDING_BARS, domainSpan * 0.02)
        val minCenter = domainMin + horizontalPadding + width / 2.0
        val maxCenter = domainMax - horizontalPadding - width / 2.0
        val centerX = if (minCenter <= maxCenter) preferredX.coerceIn(minCenter, maxCenter)
        else (domainMin + domainMax) / 2.0
        val verticalPadding = priceSpan * CARD_EDGE_PADDING_SHARE
        val topLimit = rangeMax - verticalPadding
        val bottomLimit = rangeMin + verticalPadding
        val bottom = when {
            preferredBottom + height <= topLimit -> preferredBottom
            preferredBottom - height - priceSpan * CARD_FLIP_GAP >= bottomLimit ->
                preferredBottom - height - priceSpan * CARD_FLIP_GAP
            else -> (topLimit - height).coerceAtLeast(bottomLimit)
        }
        return CardBounds(centerX - width / 2.0, bottom, centerX + width / 2.0, bottom + height)
    }

    private fun alignToCandle(
        epochSeconds: Long,
        tradePrice: Double,
        bars: List<MinuteBar>,
        timeStep: Double,
        multiplier: Double,
        displayMillis: (Long) -> Double
    ): CandleAlignment? {
        val nearest = bars.minByOrNull { kotlin.math.abs(it.minuteEpochSeconds - epochSeconds) } ?: return null
        val tradeX = displayMillis(epochSeconds)
        val candleX = displayMillis(nearest.minuteEpochSeconds)
        if (kotlin.math.abs(nearest.minuteEpochSeconds - epochSeconds) <= timeStep / 1_000.0 * 1.5) return null
        return CandleAlignment(tradeX, tradePrice, candleX, nearest.close * multiplier)
    }

    private fun addConnector(alignment: CandleAlignment) {
        val line = Path2D.Double().apply {
            moveTo(alignment.tradeX, alignment.tradeY)
            lineTo(alignment.candleX, alignment.candleY)
        }
        add(XYShapeAnnotation(line, CONNECTOR_STROKE, Color(219, 126, 35, 150)))
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
        val HIGHLIGHT_ORANGE = Color(238, 126, 18, 205)
        val HIGHLIGHT_FILL = Color(255, 190, 48, 34)
        val HIGHLIGHT_STROKE = BasicStroke(7.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val CARD_BORDER = Color(88, 69, 126, 235)
        val CARD_FILL = Color(247, 249, 251, 205)
        val CARD_CONNECTOR = Color(112, 78, 160, 205)
        val CARD_STROKE = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val CARD_CONNECTOR_STROKE = BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val CONNECTOR_STROKE = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            0f, floatArrayOf(5f, 5f), 0f)
        const val HIGHLIGHT_PADDING = 0.035
        const val CARD_WIDTH_BARS = 10.0
        const val CARD_HEIGHT_SHARE = 0.14
        const val CARD_GAP = 0.035
        const val CARD_LANE_SHARE = 0.24
        const val CARD_LEVEL_GAP = 0.012
        const val CARD_CORNER_BARS = 1.2
        const val CARD_CORNER_HEIGHT_SHARE = 0.42
        const val CARD_FLIP_GAP = 0.04
        const val CARD_EDGE_PADDING_BARS = 0.5
        const val CARD_EDGE_PADDING_SHARE = 0.02
        const val MAX_CARD_DOMAIN_SHARE = 0.90
        const val MAX_CARD_RANGE_SHARE = 0.24
        const val MAX_LEVELS = 4
    }

    private data class CandleAlignment(
        val tradeX: Double,
        val tradeY: Double,
        val candleX: Double,
        val candleY: Double
    )

    internal data class CardBounds(val left: Double, val bottom: Double, val right: Double, val top: Double) {
        val centerX: Double get() = (left + right) / 2.0
        val width: Double get() = right - left
        val height: Double get() = top - bottom
        fun contains(x: Double, y: Double): Boolean = x in left..right && y in bottom..top
    }

    private data class TradeKey(val symbol: String, val entryEpoch: Long, val exitEpoch: Long?)
    private data class HighlightAnchor(val centerX: Double, val bottom: Double, val top: Double)
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
