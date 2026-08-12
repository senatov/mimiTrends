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

internal enum class ShortMovePattern {
    DIRECTIONAL,
    POST_DROP_STRUGGLE,
    CONFIRMED_EXTENDED_DROP,
    RECOVERY_AFTER_EXTENDED_DROP
}

internal object ShortMoveDetector {
    private const val WINDOW_MINUTES = 5L
    private const val MAX_AGE_MINUTES = 10L

    fun rank(
        barsBySymbol: Map<String, List<MinuteBar>>,
        nowEpochSeconds: Long,
        limit: Int = 10
    ): List<ShortMove> = barsBySymbol.mapNotNull { (symbol, bars) ->
        detectPostDropStruggle(symbol, bars, nowEpochSeconds)
            ?: detectConfirmedExtendedDrop(symbol, bars, nowEpochSeconds)
            ?: detectRecoveryAfterExtendedDrop(symbol, bars, nowEpochSeconds)
            ?: calculate(symbol, bars, nowEpochSeconds)
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

    private fun detectConfirmedExtendedDrop(
        symbol: String,
        bars: List<MinuteBar>,
        nowEpochSeconds: Long
    ): ShortMove? {
        val recent = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = recent.lastOrNull() ?: return null
        if (latest.minuteEpochSeconds < nowEpochSeconds - MAX_AGE_MINUTES * 60) return null
        val bottomIndex = recent.indices
            .filter { index ->
                val age = latest.minuteEpochSeconds - recent[index].minuteEpochSeconds
                age in EXTENDED_CONFIRMATION_MINUTES * 60..EXTENDED_MAX_BOTTOM_AGE_MINUTES * 60
            }
            .minByOrNull { recent[it].low } ?: return null
        val bottom = recent[bottomIndex]
        val peakWindowStart = bottom.minuteEpochSeconds - EXTENDED_DROP_WINDOW_MINUTES * 60
        val peakIndex = (0 until bottomIndex)
            .filter { recent[it].minuteEpochSeconds >= peakWindowStart }
            .maxByOrNull { recent[it].high } ?: return null
        val peak = recent[peakIndex]
        val declineMinutes = (bottom.minuteEpochSeconds - peak.minuteEpochSeconds) / 60
        if (declineMinutes < EXTENDED_MIN_DECLINE_MINUTES) return null
        val dropPercent = percent(peak.high, bottom.low)
        if (dropPercent > -MIN_DROP_PERCENT) return null
        val recovery = percent(bottom.low, latest.close)
        val retainedDrop = percent(peak.high, latest.close)
        if (recovery > kotlin.math.abs(dropPercent) * MAX_RECOVERY_SHARE) return null
        if (retainedDrop > -kotlin.math.abs(dropPercent) * MIN_RETAINED_SHARE) return null
        return ShortMove(symbol, retainedDrop, peak.high, latest.close,
            peak.minuteEpochSeconds, latest.minuteEpochSeconds, recent.size - peakIndex,
            ShortMovePattern.CONFIRMED_EXTENDED_DROP, bottom.minuteEpochSeconds)
    }

    private fun percent(from: Double, to: Double): Double =
        if (from > 0.0 && to > 0.0) (to / from - 1.0) * 100.0 else Double.NaN

    private fun detectRecoveryAfterExtendedDrop(
        symbol: String,
        bars: List<MinuteBar>,
        nowEpochSeconds: Long
    ): ShortMove? {
        val recent = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = recent.lastOrNull() ?: return null
        if (latest.minuteEpochSeconds < nowEpochSeconds - MAX_AGE_MINUTES * 60) return null
        val bottomIndex = recent.indices
            .filter { index ->
                val age = latest.minuteEpochSeconds - recent[index].minuteEpochSeconds
                age in RECOVERY_MIN_BOTTOM_AGE_MINUTES * 60..RECOVERY_MAX_BOTTOM_AGE_MINUTES * 60
            }
            .minByOrNull { recent[it].low } ?: return null
        val bottom = recent[bottomIndex]
        val peakWindowStart = bottom.minuteEpochSeconds - EXTENDED_DROP_WINDOW_MINUTES * 60
        val peakIndex = (0 until bottomIndex)
            .filter { recent[it].minuteEpochSeconds >= peakWindowStart }
            .maxByOrNull { recent[it].high } ?: return null
        val peak = recent[peakIndex]
        if ((bottom.minuteEpochSeconds - peak.minuteEpochSeconds) / 60 < EXTENDED_MIN_DECLINE_MINUTES) return null
        val dropPercent = percent(peak.high, bottom.low)
        if (dropPercent > -MIN_DROP_PERCENT) return null
        val recovery = percent(bottom.low, latest.close)
        val recoveryShare = recovery / kotlin.math.abs(dropPercent)
        val retainedDrop = percent(peak.high, latest.close)
        if (recoveryShare !in RECOVERY_MIN_SHARE..RECOVERY_MAX_SHARE) return null
        if (retainedDrop > -kotlin.math.abs(dropPercent) * RECOVERY_MIN_RETAINED_SHARE) return null
        val momentumStart = recent.lastOrNull {
            it.minuteEpochSeconds <= latest.minuteEpochSeconds - RECOVERY_MOMENTUM_MINUTES * 60
        } ?: return null
        if (percent(momentumStart.close, latest.close) < RECOVERY_MIN_MOMENTUM_PERCENT) return null
        return ShortMove(symbol, retainedDrop, peak.high, latest.close,
            peak.minuteEpochSeconds, latest.minuteEpochSeconds, recent.size - peakIndex,
            ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP, bottom.minuteEpochSeconds)
    }

    private fun rankingScore(move: ShortMove, nowEpochSeconds: Long): Double {
        val freshnessEpoch = if (move.pattern == ShortMovePattern.DIRECTIONAL) {
            move.eventEpochSeconds
        } else {
            move.endedAtEpochSeconds
        }
        val ageMinutes = ((nowEpochSeconds - freshnessEpoch).coerceAtLeast(0L) / 60.0)
        val freshness = (1.0 - ageMinutes / FRESHNESS_DECAY_MINUTES).coerceAtLeast(MIN_FRESHNESS_WEIGHT)
        val patternWeight = when (move.pattern) {
            ShortMovePattern.POST_DROP_STRUGGLE -> POST_DROP_WEIGHT
            ShortMovePattern.CONFIRMED_EXTENDED_DROP -> EXTENDED_DROP_WEIGHT
            ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP -> RECOVERY_DROP_WEIGHT
            ShortMovePattern.DIRECTIONAL -> 1.0
        }
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
    private const val EXTENDED_DROP_WINDOW_MINUTES = 45L
    private const val EXTENDED_MIN_DECLINE_MINUTES = 10L
    private const val EXTENDED_CONFIRMATION_MINUTES = 25L
    private const val EXTENDED_MAX_BOTTOM_AGE_MINUTES = 35L
    private const val EXTENDED_DROP_WEIGHT = 1.75
    private const val RECOVERY_MIN_BOTTOM_AGE_MINUTES = 35L
    private const val RECOVERY_MAX_BOTTOM_AGE_MINUTES = 60L
    private const val RECOVERY_MIN_SHARE = 0.35
    private const val RECOVERY_MAX_SHARE = 0.80
    private const val RECOVERY_MIN_RETAINED_SHARE = 0.25
    private const val RECOVERY_MOMENTUM_MINUTES = 10L
    private const val RECOVERY_MIN_MOMENTUM_PERCENT = 0.25
    private const val RECOVERY_DROP_WEIGHT = 2.0
}
