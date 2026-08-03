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
import kotlin.math.sqrt

internal class SteadyRiseDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        val sorted = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = sorted.lastOrNull() ?: return null
        if (sorted.map { local(it).toLocalDate() }.distinct().size < MIN_SESSIONS) return null
        val session = sorted.filter { local(it).toLocalDate() == local(latest).toLocalDate() }
        val candidates = WINDOW_MINUTES.filter { it <= criteria.trendWindowMinutes }
            .mapNotNull { minutes -> evaluateWindow(session, minutes, criteria) }
        val best = candidates.maxByOrNull { it.score } ?: return null
        val turnover = session.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = best.score,
            priceAnomaly = Double.NaN,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = best.efficiency,
            windowChangePercent = best.returnPercent,
            windowVolume = best.bars.sumOf { it.volume },
            sessionVolume = session.sumOf { it.volume },
            sessionTurnover = turnover,
            signalAgeMinutes = 0,
            signalSource = "Steady rise ↑",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = "${best.minutes}m steady",
            signalPrice = latest.close,
            signalEpochMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun evaluateWindow(session: List<MinuteBar>, minutes: Int, criteria: ScannerCriteria): RiseWindow? {
        val bars = session.takeLast(minutes + 1)
        if (bars.size < minutes + 1 || !continuous(bars)) return null
        val totalReturn = percent(bars.first().close, bars.last().close)
        val minimumReturn = max(MIN_RETURN_PERCENT, criteria.minTrendReturnPercent * sqrt(minutes / 180.0))
        if (totalReturn < minimumReturn) return null
        val changes = bars.zipWithNext { first, second -> percent(first.close, second.close) }
        val path = changes.sumOf { abs(it) }
        val efficiency = if (path > 0.0) totalReturn / path else 0.0
        if (efficiency < max(MIN_EFFICIENCY, criteria.minTrendEfficiency)) return null
        val regression = regression(bars)
        if (regression.slope <= 0.0 || regression.rSquared < MIN_R_SQUARED) return null
        if (changes.count { it >= 0.0 }.toDouble() / changes.size < MIN_POSITIVE_STEP_RATIO) return null
        val contextBars = session.takeLast(CONTEXT_MINUTES + 1)
        if (contextBars.size >= CONTEXT_MINUTES + 1) {
            val contextRegression = regression(contextBars)
            if (contextRegression.slope <= 0.0 || percent(contextBars.first().close, contextBars.last().close) <= 0.0) return null
        }
        val latestBars = bars.takeLast(LATEST_BARS)
        val latestReturn = percent(latestBars.first().close, latestBars.last().close)
        if (latestReturn < max(MIN_LATEST_RETURN_PERCENT, totalReturn * MIN_CONTINUATION_SHARE)) return null
        if (maximumDrawdownPercent(bars) > max(MAX_DRAWDOWN_PERCENT, totalReturn * MAX_DRAWDOWN_SHARE)) return null
        val score = 1.25 + totalReturn * 1.20 + regression.rSquared * 1.25 + efficiency
        return RiseWindow(minutes, bars, totalReturn, efficiency, score)
    }

    private fun maximumDrawdownPercent(bars: List<MinuteBar>): Double {
        var peak = bars.first().close
        var drawdown = 0.0
        bars.drop(1).forEach { bar ->
            peak = max(peak, bar.close)
            drawdown = max(drawdown, -percent(peak, bar.close))
        }
        return drawdown
    }

    private fun regression(bars: List<MinuteBar>): Regression {
        val values = bars.map { it.close }
        val meanX = (values.lastIndex) / 2.0
        val meanY = values.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        values.forEachIndexed { index, value ->
            val dx = index - meanX
            val dy = value - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        val slope = if (varianceX > 0.0) covariance / varianceX else 0.0
        val rSquared = if (varianceY > 0.0) covariance * covariance / (varianceX * varianceY) else 0.0
        return Regression(slope, rSquared.coerceIn(0.0, 1.0))
    }

    private fun continuous(bars: List<MinuteBar>) = bars.zipWithNext().all { (first, second) ->
        second.minuteEpochSeconds - first.minuteEpochSeconds == 60L
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))
    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private data class RiseWindow(
        val minutes: Int,
        val bars: List<MinuteBar>,
        val returnPercent: Double,
        val efficiency: Double,
        val score: Double
    )
    private data class Regression(val slope: Double, val rSquared: Double)

    private companion object {
        val WINDOW_MINUTES = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180)
        const val MIN_SESSIONS = 2
        const val MIN_RETURN_PERCENT = 0.30
        const val MIN_EFFICIENCY = 0.45
        const val MIN_R_SQUARED = 0.55
        const val MIN_POSITIVE_STEP_RATIO = 0.52
        const val CONTEXT_MINUTES = 60
        const val LATEST_BARS = 6
        const val MIN_LATEST_RETURN_PERCENT = 0.05
        const val MIN_CONTINUATION_SHARE = 0.08
        const val MAX_DRAWDOWN_PERCENT = 0.30
        const val MAX_DRAWDOWN_SHARE = 0.40
    }
}
