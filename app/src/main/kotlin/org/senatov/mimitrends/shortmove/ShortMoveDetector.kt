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
import org.senatov.mimitrends.model.RapidMoveSettings

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
    val corridorUpper: Double? = null,
    val latestPrice: Double? = null
)

internal fun ShortMove.isActionableOpportunity(): Boolean =
    pattern == ShortMovePattern.TRADABLE_CORRIDOR && opportunityScore >= 0

internal enum class ShortMovePattern {
    RAPID_CRASH,
    RAPID_RISE,
    TRADABLE_CORRIDOR
}

internal object ShortMoveDetector {
    private const val MAX_AGE_MINUTES = 10L

    fun rank(
        barsBySymbol: Map<String, List<MinuteBar>>,
        nowEpochSeconds: Long,
        limit: Int = 10,
        settings: RapidMoveSettings = RapidMoveSettings()
    ): List<ShortMove> = barsBySymbol.mapNotNull { (symbol, bars) ->
        detectRapidCrash(symbol, bars, nowEpochSeconds, settings)
            ?: detectRapidRise(symbol, bars, nowEpochSeconds, settings)
            ?: TradableCorridorDetector.detect(symbol, bars, nowEpochSeconds)
    }.sortedByDescending { rankingScore(it, nowEpochSeconds) }.take(limit)

    private fun detectRapidCrash(
        symbol: String,
        bars: List<MinuteBar>,
        nowEpochSeconds: Long,
        settings: RapidMoveSettings
    ): ShortMove? {
        val recent = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = recent.lastOrNull()
            ?.takeIf { it.minuteEpochSeconds >= nowEpochSeconds - MAX_AGE_MINUTES * 60 }
            ?: return null
        val eligibleEnds = recent.filter {
            it.minuteEpochSeconds >= nowEpochSeconds - settings.sustainedCrashWindowMinutes * 60
        }
        val rapid = eligibleEnds.mapNotNull { end ->
            crashEndingAt(
                symbol, recent, end, settings.crashWindowMinutes.toLong(), settings.crashPercent, settings,
                anchorAtPeak = false
            )
        }
        val sustained = eligibleEnds.mapNotNull { end ->
            crashEndingAt(
                symbol, recent, end, settings.sustainedCrashWindowMinutes.toLong(), settings.sustainedCrashPercent, settings,
                anchorAtPeak = true
            )
        }
        return (rapid + sustained).minWithOrNull(
            compareBy<ShortMove>(ShortMove::changePercent).thenByDescending(ShortMove::endedAtEpochSeconds)
        )
    }

    private fun detectRapidRise(
        symbol: String,
        bars: List<MinuteBar>,
        nowEpochSeconds: Long,
        settings: RapidMoveSettings
    ): ShortMove? {
        val recent = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        if (recent.lastOrNull()?.minuteEpochSeconds ?: 0L < nowEpochSeconds - MAX_AGE_MINUTES * 60) return null
        return recent.asSequence()
            .filter { it.minuteEpochSeconds >= nowEpochSeconds - RISE_EVENT_LOOKBACK_MINUTES * 60L }
            .mapNotNull { end ->
                if (end.volumeStatus != VolumeStatus.REPORTED || end.volume <= 0.0) return@mapNotNull null
                val window = recent.filter {
                    it.minuteEpochSeconds in (end.minuteEpochSeconds - settings.riseWindowMinutes * 60L) until end.minuteEpochSeconds
                }
                val start = window.minByOrNull(MinuteBar::close) ?: return@mapNotNull null
                if (start.close <= 0.0 || end.high <= 0.0) return@mapNotNull null
                val change = percent(start.close, end.high)
                if (change < settings.risePercent - PERCENT_COMPARISON_EPSILON) return@mapNotNull null
                val eventBars = recent.filter { it.minuteEpochSeconds in start.minuteEpochSeconds..end.minuteEpochSeconds }
                if (!hasReliableActivity(recent, eventBars, end, settings, MIN_RISE_EVENT_BARS)) return@mapNotNull null
                val precedingHigh = recent.asSequence()
                    .filter {
                        it.minuteEpochSeconds in
                                (start.minuteEpochSeconds - RISE_EVENT_LOOKBACK_MINUTES * 60L) until start.minuteEpochSeconds
                    }
                    .map(MinuteBar::high).maxOrNull()
                // A close-confirmed rebound is useful even before it reclaims the earlier high.
                if (precedingHigh != null && end.high <= precedingHigh &&
                    percent(start.close, end.close) < settings.risePercent - PERCENT_COMPARISON_EPSILON
                ) return@mapNotNull null
                ShortMove(
                    symbol, change, start.close, end.high, start.minuteEpochSeconds,
                    end.minuteEpochSeconds, eventBars.size, ShortMovePattern.RAPID_RISE,
                    latestPrice = recent.last().close
                )
            }.maxWithOrNull(
                compareBy<ShortMove>(ShortMove::changePercent)
                    .thenBy(ShortMove::endedAtEpochSeconds)
            )
    }

    private fun crashEndingAt(
        symbol: String,
        bars: List<MinuteBar>,
        end: MinuteBar,
        windowMinutes: Long,
        minimumDropPercent: Double,
        settings: RapidMoveSettings,
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
        if (anchorAtPeak && elapsedMinutes > settings.crashWindowMinutes &&
            !hasAcceleratingDrop(window, start, end, settings.crashWindowMinutes.toLong())
        ) return null
        // A return from an upward spike is not a crash while the price stays above its earlier low.
        val precedingLow = bars.asSequence()
            .filter {
                it.minuteEpochSeconds in
                        (start.minuteEpochSeconds - settings.sustainedCrashWindowMinutes * 60L) until start.minuteEpochSeconds
            }
            .map(MinuteBar::close)
            .filter { it > 0.0 }
            .minOrNull()
        if (precedingLow != null && end.close >= precedingLow) return null
        val eventBars = window.filter { it.minuteEpochSeconds >= start.minuteEpochSeconds }
        if (!hasReliableActivity(bars, eventBars, end, settings, MIN_EVENT_BARS)) return null
        return ShortMove(
            symbol, change, start.close, end.close, start.minuteEpochSeconds,
            end.minuteEpochSeconds, eventBars.size, ShortMovePattern.RAPID_CRASH,
            eventEpochSeconds = end.minuteEpochSeconds
        )
    }

    private fun percent(from: Double, to: Double): Double =
        if (from > 0.0 && to > 0.0) (to / from - 1.0) * 100.0 else Double.NaN

    private fun hasAcceleratingDrop(window: List<MinuteBar>, start: MinuteBar, end: MinuteBar, rapidWindowMinutes: Long): Boolean {
        val recentStart = window.lastOrNull {
            it.minuteEpochSeconds in
                    (end.minuteEpochSeconds - rapidWindowMinutes * 60L)..
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

    private fun hasReliableActivity(
        bars: List<MinuteBar>, eventBars: List<MinuteBar>, end: MinuteBar,
        settings: RapidMoveSettings, minimumBars: Int
    ): Boolean {
        if (end.close < settings.minimumPrice || eventBars.size < minimumBars) return false
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
                active.sumOf { it.close * it.volume } >= settings.minimumTurnover
    }

    private fun rankingScore(move: ShortMove, nowEpochSeconds: Long): Double {
        val ageMinutes = ((nowEpochSeconds - move.endedAtEpochSeconds).coerceAtLeast(0L) / 60.0)
        val freshness = (1.0 - ageMinutes / FRESHNESS_DECAY_MINUTES).coerceAtLeast(MIN_FRESHNESS_WEIGHT)
        val patternWeight = when (move.pattern) {
            ShortMovePattern.RAPID_CRASH -> RAPID_CRASH_WEIGHT
            ShortMovePattern.RAPID_RISE -> RAPID_CRASH_WEIGHT
            ShortMovePattern.TRADABLE_CORRIDOR -> 2.25
        }
        return kotlin.math.abs(move.changePercent) * freshness * patternWeight
    }

    private const val MIN_CRASH_SPAN_MINUTES = 3L
    private const val MIN_RECENT_DROP_PERCENT_PER_MINUTE = 0.05
    private const val MIN_ACCELERATION_FACTOR = 1.5
    private const val MIN_EVENT_BARS = 3
    private const val MIN_RISE_EVENT_BARS = 2
    private const val MIN_REPORTED_EVENT_BARS = 2
    private const val MAX_EVENT_GAP_SECONDS = 3 * 60L
    private const val ACTIVITY_WINDOW_MINUTES = 30L
    private const val MIN_REPORTED_ACTIVITY_BARS = 3
    private const val PERCENT_COMPARISON_EPSILON = 1e-9
    private const val RAPID_CRASH_WEIGHT = 4.0
    private const val FRESHNESS_DECAY_MINUTES = 15.0
    private const val MIN_FRESHNESS_WEIGHT = 0.35
    private const val RISE_EVENT_LOOKBACK_MINUTES = 15L
}
