package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.XYBoxAnnotation
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
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date

internal class BrokerTradeAnnotations(private val plot: XYPlot) {
    fun render(
        trades: List<BrokerTrade>,
        bars: List<MinuteBar>,
        displayBars: List<MinuteBar>,
        barPriceMultiplier: Double,
        displayMillis: (Long) -> Double = { it * 1_000.0 }
    ) {
        plot.clearAnnotations()
        if (bars.isEmpty()) return
        val firstEpoch = bars.first().minuteEpochSeconds
        val lastEpoch = bars.last().minuteEpochSeconds
        val visible = trades.filter { trade ->
            trade.entryEpochSeconds in firstEpoch..lastEpoch ||
                trade.exitEpochSeconds?.let { it in firstEpoch..lastEpoch } == true
        }
        val priceSpan = (bars.maxOf { it.high } - bars.minOf { it.low })
            .coerceAtLeast(bars.last().close * 0.02) * barPriceMultiplier
        val timeStep = medianBarSeconds(displayBars) * 1_000.0
        visible.forEachIndexed { index, trade ->
            val entryX = displayMillis(trade.entryEpochSeconds)
            val exitX = displayMillis(trade.exitEpochSeconds ?: lastEpoch)
            val entryY = trade.entryPrice * barPriceMultiplier
            val exitY = (trade.exitPrice ?: trade.entryPrice) * barPriceMultiplier
            val level = index % MAX_LEVELS
            val lift = priceSpan * (0.10 + level * 0.075)
            val controlX = (entryX + exitX) / 2.0
            val controlY = maxOf(entryY, exitY) + lift
            addTradeHighlight(trade, bars, entryX, exitX, timeStep, priceSpan,
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
            addCard(trade, controlX, controlY + priceSpan * 0.025, timeStep, priceSpan,
                entryAlignment, exitAlignment)
        }
    }

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
        val low = (covered.minOfOrNull(MinuteBar::low) ?: minOf(trade.entryPrice, exitPrice)) * multiplier
        val high = (covered.maxOfOrNull(MinuteBar::high) ?: maxOf(trade.entryPrice, exitPrice)) * multiplier
        val padding = priceSpan * HIGHLIGHT_PADDING
        val left = minOf(entryX, exitX) - timeStep * 0.38
        val right = maxOf(entryX, exitX) + timeStep * 0.38
        plot.addAnnotation(XYBoxAnnotation(
            left, low - padding, right, high + padding,
            HIGHLIGHT_STROKE, HIGHLIGHT_ORANGE, HIGHLIGHT_FILL
        ))
    }

    fun clear() = plot.clearAnnotations()

    private fun addPoint(x: Double, y: Double, timeStep: Double, priceSpan: Double, color: Color) {
        val dot = Ellipse2D.Double(x - timeStep * 0.22, y - priceSpan * 0.007,
            timeStep * 0.44, priceSpan * 0.014)
        plot.addAnnotation(XYShapeAnnotation(dot, BasicStroke(1.1f), color.darker(), color))
    }

    private fun addCard(
        trade: BrokerTrade,
        x: Double,
        y: Double,
        timeStep: Double,
        priceSpan: Double,
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
        val width = timeStep * 7.5
        val height = priceSpan * 0.105
        plot.addAnnotation(XYBoxAnnotation(x - width / 2, y, x + width / 2, y + height,
            BasicStroke(0.8f), Color(255, 255, 255, 215), Color(247, 249, 251, 225)))
        plot.addAnnotation(text(title + sessionNote, x, y + height * 0.68, Color(48, 55, 63), Font.PLAIN))
        plot.addAnnotation(text(pnl, x, y + height * 0.30, pnlColor(trade), Font.BOLD))
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
        plot.addAnnotation(XYShapeAnnotation(line, CONNECTOR_STROKE, Color(219, 126, 35, 150)))
    }

    private fun text(value: String, x: Double, y: Double, color: Color, style: Int) =
        XYTextAnnotation(value, x, y).apply {
            font = Font("SansSerif", style, 10)
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
        val HIGHLIGHT_ORANGE = Color(244, 126, 24, 235)
        val HIGHLIGHT_FILL = Color(255, 153, 51, 18)
        val HIGHLIGHT_STROKE = BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val CONNECTOR_STROKE = BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            0f, floatArrayOf(5f, 5f), 0f)
        const val HIGHLIGHT_PADDING = 0.025
        const val MAX_LEVELS = 4
    }

    private data class CandleAlignment(
        val tradeX: Double,
        val tradeY: Double,
        val candleX: Double,
        val candleY: Double
    )
}
