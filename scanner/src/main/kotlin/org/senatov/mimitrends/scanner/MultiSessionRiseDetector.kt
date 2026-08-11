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

/** Finds orderly appreciation across complete trading sessions rather than a fresh impulse. */
internal class MultiSessionRiseDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        val sorted = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        val sessions = sorted.groupBy { local(it).toLocalDate() }.toSortedMap().values.toList().takeLast(SESSION_COUNT)
        if (sessions.size < SESSION_COUNT || sessions.any { it.size < MIN_BARS_PER_SESSION }) return null
        val selected = sessions.flatten()
        val latest = selected.last()
        val totalReturn = percent(selected.first().close, latest.close)
        val minimumReturn = max(MIN_TOTAL_RETURN_PERCENT, criteria.minTrendReturnPercent * 1.25)
        if (totalReturn < minimumReturn) return null

        val sessionReturns = sessions.map { percent(it.first().close, it.last().close) }
        if (sessionReturns.any { it < MIN_SESSION_RETURN_PERCENT } ||
            sessionReturns.count { it >= MIN_POSITIVE_SESSION_RETURN_PERCENT } < MIN_POSITIVE_SESSIONS) return null
        if (maximumDrawdown(selected) > MAX_DRAWDOWN_PERCENT) return null

        val changes = selected.zipWithNext().mapNotNull { (first, second) ->
            if (local(first).toLocalDate() == local(second).toLocalDate()) percent(first.close, second.close) else null
        }
        val overnightChanges = sessions.zipWithNext { first, second -> percent(first.last().close, second.first().close) }
        val path = (changes + overnightChanges).sumOf(::abs)
        val efficiency = totalReturn / path.coerceAtLeast(Double.MIN_VALUE)
        if (efficiency < max(MIN_EFFICIENCY, criteria.minTrendEfficiency)) return null
        if (changes.count { it >= 0.0 }.toDouble() / changes.size < MIN_POSITIVE_STEP_RATIO) return null
        if ((changes + overnightChanges).maxOf(::abs) > max(MAX_SINGLE_STEP_PERCENT, totalReturn * MAX_STEP_SHARE)) return null

        val fit = regression(selected)
        if (fit.slope <= 0.0 || fit.rSquared < MIN_R_SQUARED) return null
        val current = sessions.last()
        val turnover = current.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        val score = 1.5 + totalReturn * 0.9 + efficiency * 1.5 + fit.rSquared
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = score,
            priceAnomaly = Double.NaN,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = efficiency,
            windowChangePercent = totalReturn,
            windowVolume = selected.sumOf(MinuteBar::volume),
            sessionVolume = current.sumOf(MinuteBar::volume),
            sessionTurnover = turnover,
            signalAgeMinutes = 0,
            signalSource = "Steady rise ↑ · 2 sessions · long-term",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = "2 sessions steady",
            signalPrice = latest.close,
            signalEpochMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun maximumDrawdown(bars: List<MinuteBar>): Double {
        var peak = bars.first().close
        var drawdown = 0.0
        bars.drop(1).forEach { bar ->
            peak = max(peak, bar.close)
            drawdown = max(drawdown, -percent(peak, bar.close))
        }
        return drawdown
    }

    private fun regression(bars: List<MinuteBar>): Regression {
        val meanX = bars.indices.map(Int::toDouble).average()
        val meanY = bars.map(MinuteBar::close).average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        bars.forEachIndexed { index, bar ->
            val dx = index - meanX
            val dy = bar.close - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        val slope = if (varianceX > 0.0) covariance / varianceX else 0.0
        val rSquared = if (varianceX > 0.0 && varianceY > 0.0) {
            covariance * covariance / (varianceX * varianceY)
        } else 0.0
        return Regression(slope, rSquared.coerceIn(0.0, 1.0))
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private data class Regression(val slope: Double, val rSquared: Double)

    private companion object {
        const val SESSION_COUNT = 2
        const val MIN_BARS_PER_SESSION = 30
        const val MIN_TOTAL_RETURN_PERCENT = 0.60
        const val MIN_SESSION_RETURN_PERCENT = -0.15
        const val MIN_POSITIVE_SESSION_RETURN_PERCENT = 0.10
        const val MIN_POSITIVE_SESSIONS = 1
        const val MAX_DRAWDOWN_PERCENT = 1.00
        const val MIN_EFFICIENCY = 0.18
        const val MIN_POSITIVE_STEP_RATIO = 0.48
        const val MAX_SINGLE_STEP_PERCENT = 0.80
        const val MAX_STEP_SHARE = 0.45
        const val MIN_R_SQUARED = 0.55
    }
}
