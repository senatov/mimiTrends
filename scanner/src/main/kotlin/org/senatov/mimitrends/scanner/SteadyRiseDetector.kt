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
            signalSource = when (best.phase) {
                RisePhase.STEADY -> "Steady rise ↑"
                RisePhase.RECOVERY -> "Recovery rise ↑"
                RisePhase.RECOVERY_BREAKOUT -> "Recovery breakout ↑"
            },
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = "${best.minutes}m steady",
            signalPrice = latest.close,
            signalEpochMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun evaluateWindow(session: List<MinuteBar>, minutes: Int, criteria: ScannerCriteria): RiseWindow? {
        val latestEpoch = session.lastOrNull()?.minuteEpochSeconds ?: return null
        val bars = session.filter { it.minuteEpochSeconds >= latestEpoch - minutes * 60L }
        val coveredMinutes = (bars.lastOrNull()?.minuteEpochSeconds ?: return null) - bars.first().minuteEpochSeconds
        if (bars.size < minimumSamples(minutes) || coveredMinutes < minutes * 60L * MIN_WINDOW_COVERAGE ||
            !hasAcceptableGaps(bars)) return null
        val totalReturn = percent(bars.first().close, bars.last().close)
        val minimumReturn = max(MIN_RETURN_PERCENT, criteria.minTrendReturnPercent * sqrt(minutes / 180.0))
        if (totalReturn < minimumReturn) return null
        val changes = bars.zipWithNext { first, second -> percent(first.close, second.close) }
        val largestStep = changes.maxOrNull() ?: return null
        if (largestStep > totalReturn * MAX_SINGLE_STEP_SHARE) return null
        val path = changes.sumOf { abs(it) }
        val efficiency = if (path > 0.0) totalReturn / path else 0.0
        if (efficiency < max(MIN_EFFICIENCY, criteria.minTrendEfficiency)) return null
        val regression = regression(bars)
        if (regression.slope <= 0.0 || regression.rSquared < MIN_R_SQUARED) return null
        if (changes.count { it >= 0.0 }.toDouble() / changes.size < MIN_POSITIVE_STEP_RATIO) return null
        val contextBars = session.filter { it.minuteEpochSeconds >= latestEpoch - CONTEXT_MINUTES * 60L }
        var recovery = false
        if (contextBars.size >= minimumSamples(CONTEXT_MINUTES)) {
            val contextRegression = regression(contextBars)
            recovery = contextRegression.slope <= 0.0 ||
                percent(contextBars.first().close, contextBars.last().close) <= 0.0
        }
        val latestBars = bars.filter { it.minuteEpochSeconds >= latestEpoch - LATEST_MINUTES * 60L }
        if (latestBars.size < MIN_LATEST_SAMPLES) return null
        val latestReturn = percent(latestBars.first().close, latestBars.last().close)
        if (latestReturn < max(MIN_LATEST_RETURN_PERCENT, totalReturn * MIN_CONTINUATION_SHARE)) return null
        val tailBars = latestBars.filter { it.minuteEpochSeconds >= latestEpoch - TAIL_MINUTES * 60L }
        if (tailBars.size < MIN_TAIL_SAMPLES || !hasAcceptableTail(tailBars, changes)) return null
        if (maximumDrawdownPercent(bars) > max(MAX_DRAWDOWN_PERCENT, totalReturn * MAX_DRAWDOWN_SHARE)) return null
        val contextWeight = if (recovery) RECOVERY_SCORE_WEIGHT else 1.0
        val score = (1.25 + totalReturn * 1.20 + regression.rSquared * 1.25 + efficiency) * contextWeight
        val phase = when {
            !recovery -> RisePhase.STEADY
            followsConsolidation(bars, latestEpoch, latestReturn) -> RisePhase.RECOVERY_BREAKOUT
            else -> RisePhase.RECOVERY
        }
        return RiseWindow(minutes, bars, totalReturn, efficiency, score, phase)
    }

    private fun followsConsolidation(bars: List<MinuteBar>, latestEpoch: Long, latestReturn: Double): Boolean {
        if (latestReturn < MIN_BREAKOUT_RETURN_PERCENT) return false
        val preceding = bars.filter { it.minuteEpochSeconds in
            (latestEpoch - (LATEST_MINUTES + CONSOLIDATION_MINUTES) * 60L)..(latestEpoch - LATEST_MINUTES * 60L) }
        if (preceding.size < MIN_CONSOLIDATION_SAMPLES) return false
        val rangePercent = percent(preceding.minOf { it.low }, preceding.maxOf { it.high })
        return rangePercent <= MAX_CONSOLIDATION_RANGE_PERCENT && regression(preceding).rSquared < MAX_CONSOLIDATION_R_SQUARED
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

    private fun hasAcceptableTail(tailBars: List<MinuteBar>, windowChanges: List<Double>): Boolean {
        if (regression(tailBars).slope > 0.0) return true
        val tailReturn = percent(tailBars.first().close, tailBars.last().close)
        if (tailReturn >= 0.0) return tailReturn > 0.0
        val averageMinuteMove = windowChanges.map(::abs).average()
        val permittedPullback = minOf(MAX_TAIL_PULLBACK_PERCENT, averageMinuteMove)
        return -tailReturn <= permittedPullback
    }

    private fun regression(bars: List<MinuteBar>): Regression {
        val values = bars.map { it.close }
        val firstEpoch = bars.first().minuteEpochSeconds
        val xValues = bars.map { (it.minuteEpochSeconds - firstEpoch) / 60.0 }
        val meanX = xValues.average()
        val meanY = values.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        values.forEachIndexed { index, value ->
            val dx = xValues[index] - meanX
            val dy = value - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        val slope = if (varianceX > 0.0) covariance / varianceX else 0.0
        val rSquared = if (varianceY > 0.0) covariance * covariance / (varianceX * varianceY) else 0.0
        return Regression(slope, rSquared.coerceIn(0.0, 1.0))
    }

    private fun minimumSamples(minutes: Int) = max(MIN_WINDOW_SAMPLES, (minutes * MIN_SAMPLE_RATIO).toInt())

    private fun hasAcceptableGaps(bars: List<MinuteBar>) = bars.zipWithNext().all { (first, second) ->
        second.minuteEpochSeconds - first.minuteEpochSeconds <= MAX_GAP_MINUTES * 60L
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))
    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private data class RiseWindow(
        val minutes: Int,
        val bars: List<MinuteBar>,
        val returnPercent: Double,
        val efficiency: Double,
        val score: Double,
        val phase: RisePhase
    )
    private data class Regression(val slope: Double, val rSquared: Double)
    private enum class RisePhase { STEADY, RECOVERY, RECOVERY_BREAKOUT }

    private companion object {
        val WINDOW_MINUTES = listOf(10, 15, 20, 30, 45, 60, 90, 120, 180)
        const val MIN_SESSIONS = 2
        const val MIN_RETURN_PERCENT = 0.30
        const val MIN_EFFICIENCY = 0.45
        const val MIN_R_SQUARED = 0.55
        const val MIN_POSITIVE_STEP_RATIO = 0.52
        const val MAX_SINGLE_STEP_SHARE = 0.60
        const val CONTEXT_MINUTES = 60
        const val LATEST_MINUTES = 5
        const val MIN_LATEST_SAMPLES = 3
        const val TAIL_MINUTES = 3
        const val MIN_TAIL_SAMPLES = 3
        const val MAX_TAIL_PULLBACK_PERCENT = 0.05
        const val MIN_LATEST_RETURN_PERCENT = 0.05
        const val MIN_CONTINUATION_SHARE = 0.08
        const val MAX_DRAWDOWN_PERCENT = 0.30
        const val MAX_DRAWDOWN_SHARE = 0.40
        const val MIN_WINDOW_COVERAGE = 0.70
        const val MIN_SAMPLE_RATIO = 0.55
        const val MIN_WINDOW_SAMPLES = 6
        const val MAX_GAP_MINUTES = 5
        const val RECOVERY_SCORE_WEIGHT = 0.85
        const val CONSOLIDATION_MINUTES = 10
        const val MIN_CONSOLIDATION_SAMPLES = 6
        const val MIN_BREAKOUT_RETURN_PERCENT = 0.12
        const val MAX_CONSOLIDATION_RANGE_PERCENT = 0.20
        const val MAX_CONSOLIDATION_R_SQUARED = 0.35
    }
}
