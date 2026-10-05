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
import org.senatov.mimitrends.model.VolumeStatus

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
        val recent = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = recent.lastOrNull()
            ?.takeIf { it.minuteEpochSeconds >= nowEpochSeconds - MAX_AGE_MINUTES * 60 }
            ?: return null
        val eligibleEnds = recent.filter {
            it.minuteEpochSeconds >= nowEpochSeconds - CRASH_EVENT_LOOKBACK_MINUTES * 60
        }
        val rapid = eligibleEnds.mapNotNull { end ->
            crashEndingAt(
                symbol, recent, end, RAPID_MOVE_WINDOW_MINUTES, RAPID_CRASH_MIN_PERCENT,
                anchorAtPeak = false
            )
        }
        val sustained = eligibleEnds.mapNotNull { end ->
            crashEndingAt(
                symbol, recent, end, SUSTAINED_DROP_WINDOW_MINUTES, SUSTAINED_DROP_MIN_PERCENT,
                anchorAtPeak = true
            )
        }
        return (rapid + sustained).minWithOrNull(
            compareBy<ShortMove>(ShortMove::changePercent).thenByDescending(ShortMove::endedAtEpochSeconds)
        )
    }

    private fun crashEndingAt(
        symbol: String,
        bars: List<MinuteBar>,
        end: MinuteBar,
        windowMinutes: Long,
        minimumDropPercent: Double,
        anchorAtPeak: Boolean
    ): ShortMove? {
        val windowStart = end.minuteEpochSeconds - windowMinutes * 60L
        val window = bars.filter { it.minuteEpochSeconds in windowStart..end.minuteEpochSeconds }
        val minimumStartEpoch = end.minuteEpochSeconds - (windowMinutes - 1L) * 60L
        val start = if (anchorAtPeak) {
            window.maxByOrNull(MinuteBar::close)
        } else {
            window.lastOrNull { it.minuteEpochSeconds <= minimumStartEpoch }
        }
        if (start == null) return null
        val elapsedMinutes = (end.minuteEpochSeconds - start.minuteEpochSeconds) / 60L
        if (elapsedMinutes < MIN_CRASH_SPAN_MINUTES || start.close <= 0.0 || end.close <= 0.0) return null
        val change = percent(start.close, end.close)
        if (change > -minimumDropPercent + PERCENT_COMPARISON_EPSILON) return null
        if (anchorAtPeak && elapsedMinutes > RAPID_MOVE_WINDOW_MINUTES &&
            !hasAcceleratingDrop(window, start, end)) return null
        // A return from an upward spike is not a crash while the price stays above its earlier low.
        val precedingLow = bars.asSequence()
            .filter {
                it.minuteEpochSeconds in
                    (start.minuteEpochSeconds - SUSTAINED_DROP_WINDOW_MINUTES * 60L) until start.minuteEpochSeconds
            }
            .map(MinuteBar::close)
            .filter { it > 0.0 }
            .minOrNull()
        if (precedingLow != null && end.close >= precedingLow) return null
        val eventBars = window.filter { it.minuteEpochSeconds >= start.minuteEpochSeconds }
        if (!hasReliableActivity(bars, eventBars, end)) return null
        return ShortMove(
            symbol, change, start.close, end.close, start.minuteEpochSeconds,
            end.minuteEpochSeconds, eventBars.size, ShortMovePattern.RAPID_CRASH,
            eventEpochSeconds = end.minuteEpochSeconds
        )
    }

    private fun percent(from: Double, to: Double): Double =
        if (from > 0.0 && to > 0.0) (to / from - 1.0) * 100.0 else Double.NaN

    private fun hasAcceleratingDrop(window: List<MinuteBar>, start: MinuteBar, end: MinuteBar): Boolean {
        val recentStart = window.lastOrNull {
            it.minuteEpochSeconds in
                (end.minuteEpochSeconds - RAPID_MOVE_WINDOW_MINUTES * 60L)..
                    (end.minuteEpochSeconds - MIN_CRASH_SPAN_MINUTES * 60L)
        } ?: return false
        val earlierMinutes = (recentStart.minuteEpochSeconds - start.minuteEpochSeconds) / 60.0
        val recentMinutes = (end.minuteEpochSeconds - recentStart.minuteEpochSeconds) / 60.0
        if (earlierMinutes < MIN_CRASH_SPAN_MINUTES || recentMinutes <= 0.0) return false
        val recentRate = -percent(recentStart.close, end.close) / recentMinutes
        val earlierRate = -percent(start.close, recentStart.close) / earlierMinutes
        return recentRate >= MIN_RECENT_DROP_PERCENT_PER_MINUTE &&
            recentRate >= earlierRate.coerceAtLeast(0.0) * MIN_ACCELERATION_FACTOR
    }

    private fun hasReliableActivity(bars: List<MinuteBar>, eventBars: List<MinuteBar>, end: MinuteBar): Boolean {
        if (end.close < MIN_CRASH_PRICE || eventBars.size < MIN_EVENT_BARS) return false
        if (eventBars.zipWithNext().any { (first, second) ->
                second.minuteEpochSeconds - first.minuteEpochSeconds > MAX_EVENT_GAP_SECONDS
            }) return false
        if (eventBars.count { it.volumeStatus == VolumeStatus.REPORTED && it.volume > 0.0 } <
            MIN_REPORTED_EVENT_BARS) return false
        val active = bars.filter {
            it.minuteEpochSeconds in (end.minuteEpochSeconds - ACTIVITY_WINDOW_MINUTES * 60L)..end.minuteEpochSeconds &&
                it.volumeStatus == VolumeStatus.REPORTED && it.volume > 0.0
        }
        return active.size >= MIN_REPORTED_ACTIVITY_BARS &&
            active.sumOf { it.close * it.volume } >= MIN_ACTIVITY_TURNOVER
    }

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
    private const val RAPID_CRASH_MIN_PERCENT = 0.30
    private const val SUSTAINED_DROP_WINDOW_MINUTES = 15L
    private const val SUSTAINED_DROP_MIN_PERCENT = 0.60
    private const val CRASH_EVENT_LOOKBACK_MINUTES = 15L
    private const val MIN_CRASH_SPAN_MINUTES = 3L
    private const val MIN_RECENT_DROP_PERCENT_PER_MINUTE = 0.05
    private const val MIN_ACCELERATION_FACTOR = 1.5
    private const val MIN_CRASH_PRICE = 5.0
    private const val MIN_EVENT_BARS = 3
    private const val MIN_REPORTED_EVENT_BARS = 2
    private const val MAX_EVENT_GAP_SECONDS = 3 * 60L
    private const val ACTIVITY_WINDOW_MINUTES = 30L
    private const val MIN_REPORTED_ACTIVITY_BARS = 3
    private const val MIN_ACTIVITY_TURNOVER = 250_000.0
    private const val PERCENT_COMPARISON_EPSILON = 1e-9
    private const val RAPID_CRASH_WEIGHT = 4.0
    private const val FRESHNESS_DECAY_MINUTES = 15.0
    private const val MIN_FRESHNESS_WEIGHT = 0.35
}
