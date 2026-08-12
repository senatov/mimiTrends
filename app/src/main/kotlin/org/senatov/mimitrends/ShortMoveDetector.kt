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
    val pattern: ShortMovePattern = ShortMovePattern.DIRECTIONAL
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
    }.sortedWith(compareByDescending<ShortMove> { it.pattern == ShortMovePattern.POST_DROP_STRUGGLE }
        .thenByDescending { kotlin.math.abs(it.changePercent) }).take(limit)

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
                    ShortMovePattern.POST_DROP_STRUGGLE)
            }
        }
        return null
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
    private const val MAX_RECOVERY_SHARE = 0.60
    private const val MIN_RETAINED_SHARE = 0.50
}
