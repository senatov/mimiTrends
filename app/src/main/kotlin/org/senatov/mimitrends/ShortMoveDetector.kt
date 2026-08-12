package org.senatov.mimitrends

import org.senatov.mimitrends.model.MinuteBar

internal data class ShortMove(
    val symbol: String,
    val changePercent: Double,
    val open: Double,
    val close: Double,
    val startedAtEpochSeconds: Long,
    val endedAtEpochSeconds: Long,
    val barCount: Int,
    val pattern: ShortMovePattern = ShortMovePattern.DIRECTIONAL,
    val eventEpochSeconds: Long = endedAtEpochSeconds
)

internal enum class ShortMovePattern { DIRECTIONAL, POST_DROP_STRUGGLE }

internal object ShortMoveDetector {
    private const val WINDOW_MINUTES = 5L
    private const val MAX_AGE_MINUTES = 10L

    fun rank(
        barsBySymbol: Map<String, List<MinuteBar>>,
        nowEpochSeconds: Long,
        limit: Int = 10
    ): List<ShortMove> = barsBySymbol.mapNotNull { (symbol, bars) ->
        detectPostDropStruggle(symbol, bars, nowEpochSeconds) ?: calculate(symbol, bars, nowEpochSeconds)
    }.sortedByDescending { rankingScore(it, nowEpochSeconds) }.take(limit)

    private fun detectPostDropStruggle(
        symbol: String,
        bars: List<MinuteBar>,
        nowEpochSeconds: Long
    ): ShortMove? {
        val latest = bars.maxByOrNull(MinuteBar::minuteEpochSeconds) ?: return null
        if (latest.minuteEpochSeconds < nowEpochSeconds - MAX_AGE_MINUTES * 60) return null
        val recent = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        if (recent.size < 4) return null
        for (dropEnd in recent.lastIndex - 2 downTo 1) {
            if (recent[dropEnd].minuteEpochSeconds < latest.minuteEpochSeconds - POST_DROP_MAX_AGE_MINUTES * 60) break
            val postBars = recent.size - dropEnd - 1
            if (postBars < 2) continue
            val dropWindowStart = recent[dropEnd].minuteEpochSeconds - (DROP_WINDOW_MINUTES - 1) * 60L
            val dropStart = (0..dropEnd).asSequence()
                .filter { recent[it].minuteEpochSeconds >= dropWindowStart }
                .maxByOrNull { recent[it].high } ?: continue
            val startPrice = recent[dropStart].high
            val bottomPrice = recent[dropEnd].low
            if (startPrice <= 0.0 || bottomPrice <= 0.0 || latest.close <= 0.0) continue
            val dropPercent = (bottomPrice / startPrice - 1.0) * 100.0
            if (dropPercent > -MIN_DROP_PERCENT) continue
            val recovery = (latest.close / bottomPrice - 1.0) * 100.0
            val retainedDrop = (latest.close / startPrice - 1.0) * 100.0
            val remainsNearLow = recovery <= kotlin.math.abs(dropPercent) * MAX_RECOVERY_SHARE
            val dropRetained = retainedDrop <= -kotlin.math.abs(dropPercent) * MIN_RETAINED_SHARE
            if (remainsNearLow && dropRetained) {
                return ShortMove(symbol, retainedDrop, startPrice, latest.close,
                    recent[dropStart].minuteEpochSeconds, latest.minuteEpochSeconds, recent.size - dropStart,
                    ShortMovePattern.POST_DROP_STRUGGLE, recent[dropEnd].minuteEpochSeconds)
            }
        }
        return null
    }

    private fun rankingScore(move: ShortMove, nowEpochSeconds: Long): Double {
        val ageMinutes = ((nowEpochSeconds - move.eventEpochSeconds).coerceAtLeast(0L) / 60.0)
        val freshness = (1.0 - ageMinutes / FRESHNESS_DECAY_MINUTES).coerceAtLeast(MIN_FRESHNESS_WEIGHT)
        val patternWeight = if (move.pattern == ShortMovePattern.POST_DROP_STRUGGLE) POST_DROP_WEIGHT else 1.0
        return kotlin.math.abs(move.changePercent) * freshness * patternWeight
    }

    private fun calculate(symbol: String, bars: List<MinuteBar>, nowEpochSeconds: Long): ShortMove? {
        val latest = bars.maxByOrNull(MinuteBar::minuteEpochSeconds) ?: return null
        if (latest.minuteEpochSeconds < nowEpochSeconds - MAX_AGE_MINUTES * 60) return null
        val windowStart = latest.minuteEpochSeconds - (WINDOW_MINUTES - 1) * 60
        val window = bars.asSequence()
            .filter { it.minuteEpochSeconds in windowStart..latest.minuteEpochSeconds }
            .sortedBy(MinuteBar::minuteEpochSeconds)
            .toList()
        val first = window.firstOrNull() ?: return null
        if (window.size < 2 || first.open <= 0.0 || latest.close <= 0.0) return null
        val change = (latest.close / first.open - 1.0) * 100.0
        if (!change.isFinite()) return null
        return ShortMove(symbol, change, first.open, latest.close,
            first.minuteEpochSeconds, latest.minuteEpochSeconds, window.size)
    }

    private const val MIN_DROP_PERCENT = 0.7
    private const val DROP_WINDOW_MINUTES = 5L
    private const val POST_DROP_MAX_AGE_MINUTES = 15L
    private const val FRESHNESS_DECAY_MINUTES = 15.0
    private const val MIN_FRESHNESS_WEIGHT = 0.35
    private const val POST_DROP_WEIGHT = 1.10
    private const val MAX_RECOVERY_SHARE = 0.60
    private const val MIN_RETAINED_SHARE = 0.50
}
