package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.senatov.mimitrends.model.MinuteBar

internal data class ShortMove(
    val symbol: String,
    val changePercent: Double,
    val open: Double,
    val close: Double,
    val startedAtEpochSeconds: Long,
    val endedAtEpochSeconds: Long,
    val barCount: Int,
    val pattern: ShortMovePattern,
    val eventEpochSeconds: Long = endedAtEpochSeconds,
    val opportunityScore: Int = -1,
    val opportunityDetails: String = "",
    val isRetained: Boolean = false,
    val corridorLower: Double? = null,
    val corridorUpper: Double? = null
)

internal fun ShortMove.isActionableOpportunity(): Boolean =
    pattern == ShortMovePattern.TRADABLE_CORRIDOR && opportunityScore >= 0

internal enum class ShortMovePattern {
    RAPID_CRASH,
    TRADABLE_CORRIDOR
}

internal object ShortMoveDetector {
    private const val MAX_AGE_MINUTES = 10L

    fun rank(
        barsBySymbol: Map<String, List<MinuteBar>>,
        nowEpochSeconds: Long,
        limit: Int = 10
    ): List<ShortMove> = barsBySymbol.mapNotNull { (symbol, bars) ->
        detectRapidCrash(symbol, bars, nowEpochSeconds)
            ?: TradableCorridorDetector.detect(symbol, bars, nowEpochSeconds)
    }.sortedByDescending { rankingScore(it, nowEpochSeconds) }.take(limit)

    private fun detectRapidCrash(symbol: String, bars: List<MinuteBar>, nowEpochSeconds: Long): ShortMove? {
        val move = detectRapidMove(symbol, bars, nowEpochSeconds) ?: return null
        return move.takeIf { it.changePercent <= -RAPID_CRASH_MIN_PERCENT + PERCENT_COMPARISON_EPSILON }
            ?.copy(pattern = ShortMovePattern.RAPID_CRASH)
    }

    private fun detectRapidMove(symbol: String, bars: List<MinuteBar>, nowEpochSeconds: Long): ShortMove? {
        val recent = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = recent.lastOrNull()?.takeIf { it.minuteEpochSeconds >= nowEpochSeconds - MAX_AGE_MINUTES * 60 } ?: return null
        val windowStart = latest.minuteEpochSeconds - RAPID_MOVE_WINDOW_MINUTES * 60L
        val start = recent.lastOrNull {
            it.minuteEpochSeconds in windowStart..latest.minuteEpochSeconds - (RAPID_MOVE_WINDOW_MINUTES - 1) * 60L
        }
            ?: return null
        if (start.close <= 0.0 || latest.close <= 0.0) return null
        val change = percent(start.close, latest.close)
        val window = recent.filter { it.minuteEpochSeconds in start.minuteEpochSeconds..latest.minuteEpochSeconds }
        return ShortMove(
            symbol, change, start.close, latest.close, start.minuteEpochSeconds,
            latest.minuteEpochSeconds, window.size, ShortMovePattern.RAPID_CRASH,
            eventEpochSeconds = latest.minuteEpochSeconds
        )
    }

    private fun percent(from: Double, to: Double): Double =
        if (from > 0.0 && to > 0.0) (to / from - 1.0) * 100.0 else Double.NaN

    private fun rankingScore(move: ShortMove, nowEpochSeconds: Long): Double {
        val ageMinutes = ((nowEpochSeconds - move.endedAtEpochSeconds).coerceAtLeast(0L) / 60.0)
        val freshness = (1.0 - ageMinutes / FRESHNESS_DECAY_MINUTES).coerceAtLeast(MIN_FRESHNESS_WEIGHT)
        val patternWeight = when (move.pattern) {
            ShortMovePattern.RAPID_CRASH -> RAPID_CRASH_WEIGHT
            ShortMovePattern.TRADABLE_CORRIDOR -> 2.25
        }
        return kotlin.math.abs(move.changePercent) * freshness * patternWeight
    }

    private const val RAPID_MOVE_WINDOW_MINUTES = 4L
    private const val RAPID_CRASH_MIN_PERCENT = 0.50
    private const val PERCENT_COMPARISON_EPSILON = 1e-9
    private const val RAPID_CRASH_WEIGHT = 4.0
    private const val FRESHNESS_DECAY_MINUTES = 15.0
    private const val MIN_FRESHNESS_WEIGHT = 0.35
}
