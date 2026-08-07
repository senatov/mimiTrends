package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max

/** Separates a valid rising trend from a favorable current entry point. */
internal class EntryTimingClassifier(private val zoneOverride: ZoneId? = null) {
    fun classify(bars: List<MinuteBar>, result: ScanResult): ScanResult {
        if ('↑' !in result.signalSource || !isTrend(result)) return result
        val latest = bars.lastOrNull() ?: return result
        val latestDate = local(latest).toLocalDate()
        val session = bars.filter { local(it).toLocalDate() == latestDate }
        val requestedMinutes = result.signalWindowLabel.filter(Char::isDigit).toIntOrNull() ?: DEFAULT_WINDOW_MINUTES
        val window = session.filter {
            it.minuteEpochSeconds >= latest.minuteEpochSeconds - requestedMinutes.coerceAtLeast(MIN_WINDOW_MINUTES) * 60L
        }
        if (window.size < MIN_BARS) return result
        val baseline = window.dropLast(RECENT_BARS)
        if (baseline.size < MIN_BASELINE_BARS) return result
        val baselineFit = regression(baseline)
        if (baselineFit.slope <= 0.0) return result
        val expected = baselineFit.intercept + baselineFit.slope * minutesBetween(window.first(), latest)
        if (expected <= 0.0) return result
        val extensionPercent = percent(expected, latest.close)
        val typicalRangePercent = window.map { (it.high - it.low) / it.close * 100.0 }.sorted()
            .let { it[it.size / 2] }
        val extensionLimit = max(MIN_EXTENSION_PERCENT, typicalRangePercent * RANGE_EXTENSION_MULTIPLIER)
        val nearHigh = latest.close >= window.maxOf(MinuteBar::high) * (1.0 - MAX_DISTANCE_FROM_HIGH_PERCENT / 100.0)
        val recent = window.takeLast(RECENT_BARS + 1)
        val recentSlope = regression(recent).slope
        val recentReturn = percent(recent.first().close, recent.last().close)
        val decelerating = recentSlope <= 0.0 || recentReturn <= MAX_FLAT_RECENT_RETURN_PERCENT
        val totalReturn = percent(window.first().close, latest.close)
        val extended = (extensionPercent >= extensionLimit && nearHigh) ||
            (totalReturn >= MIN_MATURE_RETURN_PERCENT && nearHigh && decelerating)
        return if (extended) result.withQualifier(EXTENDED_QUALIFIER) else result
    }

    private fun isTrend(result: ScanResult): Boolean =
        result.signalSource.startsWith("Steady rise") || result.signalSource.startsWith("Recovery") ||
            result.signalSource.startsWith("Trend")

    private fun ScanResult.withQualifier(qualifier: String): ScanResult =
        if (signalSource.contains(qualifier)) this else copy(signalSource = "$signalSource · $qualifier")

    private fun regression(bars: List<MinuteBar>): Regression {
        val first = bars.first()
        val x = bars.map { minutesBetween(first, it) }
        val meanX = x.average()
        val meanY = bars.map(MinuteBar::close).average()
        val denominator = x.sumOf { (it - meanX) * (it - meanX) }
        val slope = if (denominator > 0.0) bars.indices.sumOf { index ->
            (x[index] - meanX) * (bars[index].close - meanY)
        } / denominator else 0.0
        return Regression(meanY - slope * meanX, slope)
    }

    private fun minutesBetween(first: MinuteBar, second: MinuteBar): Double =
        (second.minuteEpochSeconds - first.minuteEpochSeconds) / 60.0

    private fun percent(first: Double, last: Double): Double = (last / first - 1.0) * 100.0

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private data class Regression(val intercept: Double, val slope: Double)

    private companion object {
        const val EXTENDED_QUALIFIER = "extended · wait for pullback"
        const val DEFAULT_WINDOW_MINUTES = 60
        const val MIN_WINDOW_MINUTES = 30
        const val MIN_BARS = 24
        const val MIN_BASELINE_BARS = 18
        const val RECENT_BARS = 5
        const val MIN_EXTENSION_PERCENT = 0.20
        const val RANGE_EXTENSION_MULTIPLIER = 1.5
        const val MAX_DISTANCE_FROM_HIGH_PERCENT = 0.15
        const val MAX_FLAT_RECENT_RETURN_PERCENT = 0.02
        const val MIN_MATURE_RETURN_PERCENT = 0.75
    }
}
