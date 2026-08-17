package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.isValidMinuteBar
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/** Supplies explicitly labelled positive or neutral context when no actionable anomaly is present. */
internal class MarketContextDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        val sorted = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = sorted.lastOrNull() ?: return null
        val session = sorted.filter { local(it).toLocalDate() == local(latest).toLocalDate() }
        val window = session.filter { it.minuteEpochSeconds >= latest.minuteEpochSeconds - WINDOW_MINUTES * 60L }
        if (window.size < MIN_BARS || window.last().minuteEpochSeconds - window.first().minuteEpochSeconds < MIN_SPAN_SECONDS) {
            return null
        }
        val change = percent(window.first().close, latest.close)
        val recent = window.takeLast(minOf(3, window.size))
        val recentChange = percent(recent.first().close, recent.last().close)
        val drawdown = -percent(window.maxOf(MinuteBar::high), latest.close)
        if (change < MIN_WINDOW_RETURN || recentChange < MIN_RECENT_RETURN || drawdown > MAX_DRAWDOWN) return null
        val steps = window.zipWithNext { first, second -> percent(first.close, second.close) }
        val efficiency = abs(change) / steps.sumOf(::abs).coerceAtLeast(abs(change).coerceAtLeast(0.01))
        val turnover = session.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        val positive = change >= POSITIVE_RETURN
        val score = 2.5 + max(0.0, change) * 1.5 + efficiency.coerceIn(0.0, 1.0)
        return ScanResult(
            symbol = symbol, price = latest.close, anomalyScore = score,
            priceAnomaly = Double.NaN, volumeAnomaly = Double.NaN, rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN, candleBodyRatio = efficiency.coerceIn(0.0, 1.0),
            windowChangePercent = change, windowVolume = window.sumOf(MinuteBar::volume),
            sessionVolume = session.sumOf(MinuteBar::volume), sessionTurnover = turnover,
            signalAgeMinutes = 0,
            signalSource = if (positive) "Positive context ↑ · watch" else "Neutral context · watch",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            dataStatus = "CONTEXT", signalWindowLabel = "${WINDOW_MINUTES}m context",
            signalPrice = latest.close, signalEpochMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private companion object {
        const val WINDOW_MINUTES = 30
        const val MIN_BARS = 5
        const val MIN_SPAN_SECONDS = 15 * 60L
        const val MIN_WINDOW_RETURN = -0.10
        const val MIN_RECENT_RETURN = -0.08
        const val MAX_DRAWDOWN = 0.60
        const val POSITIVE_RETURN = 0.15
    }
}
