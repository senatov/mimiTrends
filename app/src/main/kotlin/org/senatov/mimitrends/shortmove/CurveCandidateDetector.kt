package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.model.CurveCandidate
import org.senatov.mimitrends.model.CurveDirection
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

internal object CurveCandidateDetector {
    fun detect(symbol: String, bars: List<MinuteBar>, nowEpochSeconds: Long): CurveCandidate? {
        val recent = bars.asSequence()
            .filter { it.minuteEpochSeconds in (nowEpochSeconds - LOOKBACK_MINUTES * 60L)..nowEpochSeconds }
            .sortedBy(MinuteBar::minuteEpochSeconds)
            .toList()
        if (recent.size < MIN_BARS || recent.last().minuteEpochSeconds < nowEpochSeconds - MAX_DATA_AGE_SECONDS) return null
        return recent.asSequence()
            .filter { it.minuteEpochSeconds >= nowEpochSeconds - EVENT_AGE_MINUTES * 60L }
            .flatMap { end ->
                CurveDirection.entries.asSequence().mapNotNull { direction ->
                    candidate(symbol, recent, end, direction)
                }
            }
            .maxByOrNull { abs(it.changePercent) / sqrt(it.durationMinutes.toDouble()) }
    }

    private fun candidate(
        symbol: String, bars: List<MinuteBar>, end: MinuteBar, direction: CurveDirection
    ): CurveCandidate? {
        val possibleStarts = bars.filter { start ->
            val elapsed = end.minuteEpochSeconds - start.minuteEpochSeconds
            elapsed in (MIN_DURATION_MINUTES * 60L)..(MAX_DURATION_MINUTES * 60L) && start.close > 0.0
        }
        val start = when (direction) {
            CurveDirection.RISE -> possibleStarts.minByOrNull(MinuteBar::close)
            CurveDirection.DROP -> possibleStarts.maxByOrNull(MinuteBar::close)
        } ?: return null
        val segment = bars.filter { it.minuteEpochSeconds in start.minuteEpochSeconds..end.minuteEpochSeconds }
        if (segment.size < MIN_BARS || segment.zipWithNext().any { (a, b) ->
                b.minuteEpochSeconds - a.minuteEpochSeconds > MAX_GAP_SECONDS
            }) return null
        val endPrice = if (direction == CurveDirection.RISE) end.high else end.close
        if (endPrice <= 0.0 || end.close <= 0.0) return null
        val change = (endPrice / start.close - 1.0) * 100.0
        if (direction == CurveDirection.RISE && change < MIN_RISE_PERCENT ||
            direction == CurveDirection.DROP && change > -MIN_DROP_PERCENT
        ) return null
        val returns = segment.zipWithNext().map { (a, b) ->
            if (a.close > 0.0 && b.close > 0.0) ln(b.close / a.close) * 100.0 else 0.0
        }
        val average = returns.average()
        val volatility = sqrt(returns.sumOf { (it - average) * (it - average) } / returns.size)
        val midpoint = returns.size / 2
        val earlierRate = returns.take(midpoint).average()
        val recentRate = returns.drop(midpoint).average()
        val pathLength = returns.sumOf(::abs)
        val closeChange = abs(ln(end.close / start.close) * 100.0)
        val reversal = abs(endPrice / end.close - 1.0) * 100.0
        return CurveCandidate(
            symbol = symbol.uppercase(), direction = direction,
            anchorEpochSeconds = start.minuteEpochSeconds, endEpochSeconds = end.minuteEpochSeconds,
            startPrice = start.close, endPrice = endPrice, changePercent = change,
            durationMinutes = ((end.minuteEpochSeconds - start.minuteEpochSeconds) / 60L).toInt(),
            volatilityPercent = volatility,
            accelerationPercentPerMinute = recentRate - earlierRate,
            pathEfficiency = if (pathLength > 0.0) (closeChange / pathLength).coerceIn(0.0, 1.0) else 0.0,
            reversalPercent = reversal,
            reportedVolumeBars = segment.count { it.volumeStatus == VolumeStatus.REPORTED && it.volume > 0.0 },
            turnover = segment.sumOf { if (it.volumeStatus == VolumeStatus.REPORTED) it.close * it.volume else 0.0 },
            barCount = segment.size
        )
    }

    private const val LOOKBACK_MINUTES = 20
    private const val EVENT_AGE_MINUTES = 8
    private const val MAX_DATA_AGE_SECONDS = 10 * 60L
    private const val MIN_DURATION_MINUTES = 2
    private const val MAX_DURATION_MINUTES = 10
    private const val MAX_GAP_SECONDS = 3 * 60L
    private const val MIN_BARS = 3
    private const val MIN_RISE_PERCENT = 0.40
    private const val MIN_DROP_PERCENT = 0.20
}