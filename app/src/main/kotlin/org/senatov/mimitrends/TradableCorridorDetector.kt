package org.senatov.mimitrends

import kotlin.math.abs
import kotlin.math.roundToInt
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketTimeZone
import java.time.Instant
import org.senatov.mimitrends.statistics.ValidatedStatistics

/** Finds a stable intraday range whose remaining move is still usable after estimated friction. */
internal object TradableCorridorDetector {
    fun detect(symbol: String, bars: List<MinuteBar>, nowEpochSeconds: Long): ShortMove? {
        val sorted = bars.filter { it.close > 0.0 }.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = sorted.lastOrNull() ?: return null
        if (nowEpochSeconds - latest.minuteEpochSeconds > MAX_DATA_AGE_MINUTES * 60) return null
        val zone = MarketTimeZone.forSymbol(symbol)
        val latestDate = Instant.ofEpochSecond(latest.minuteEpochSeconds).atZone(zone).toLocalDate()
        val cutoff = latest.minuteEpochSeconds - LOOKBACK_MINUTES * 60L
        val session = sorted.filter {
            it.minuteEpochSeconds >= cutoff &&
                    Instant.ofEpochSecond(it.minuteEpochSeconds).atZone(zone).toLocalDate() == latestDate
        }
        if (session.size < MIN_BARS) return null

        val closes = session.map(MinuteBar::close)
        val lower = ValidatedStatistics.quantile(closes, LOWER_QUANTILE)
        val upper = ValidatedStatistics.quantile(closes, UPPER_QUANTILE)
        if (lower <= 0.0 || upper <= lower) return null
        val widthPercent = (upper / lower - 1.0) * 100.0
        if (widthPercent !in MIN_WIDTH_PERCENT..MAX_WIDTH_PERCENT) return null

        val tolerance = (upper - lower) * EDGE_TOLERANCE_SHARE
        if (latest.close !in (lower - tolerance)..(upper + tolerance)) return null
        val recentBreaks = session.takeLast(RECENT_STABILITY_BARS)
            .count { it.close !in (lower - tolerance)..(upper + tolerance) }
        if (recentBreaks > MAX_RECENT_BREAKS) return null
        val lowerTouches = touchGroups(session) { it.low in (lower - tolerance)..(lower + tolerance) }
        val upperTouches = touchGroups(session) { it.high in (upper - tolerance)..(upper + tolerance) }
        if (lowerTouches < MIN_TOUCHES_PER_EDGE || upperTouches < MIN_TOUCHES_PER_EDGE) return null
        val contained = session.count { it.close in (lower - tolerance)..(upper + tolerance) }.toDouble() / session.size
        if (contained < MIN_CONTAINMENT) return null

        val half = (upper - lower) / 2.0
        val drift = abs(
            session.takeLast(10).map(MinuteBar::close).average() -
                    session.take(10).map(MinuteBar::close).average()
        ) / half
        if (drift > MAX_NORMALIZED_DRIFT) return null

        val position = ((latest.close - lower) / (upper - lower)).coerceIn(0.0, 1.0)
        val remaining = ((upper - latest.close) / latest.close * 100.0).coerceAtLeast(0.0)
        val edgeScore = when {
            position <= 0.20 -> 1.0
            position <= 0.50 -> 1.0 - (position - 0.20) / 0.30 * 0.45
            else -> (1.0 - position).coerceAtLeast(0.0) * 0.70
        }
        val widthScore = ((widthPercent - MIN_WIDTH_PERCENT) / WIDTH_FULL_SCORE_PERCENT).coerceIn(0.0, 1.0)
        val repeatability = ((lowerTouches + upperTouches) / 8.0).coerceIn(0.0, 1.0)
        val score = ((0.45 * edgeScore + 0.25 * widthScore + 0.20 * repeatability +
                0.10 * contained) * 100.0).roundToInt().coerceIn(0, 100)
        if (score < MIN_OPPORTUNITY_SCORE || remaining < MIN_REMAINING_PERCENT) return null

        val details = "Stable %.2f%% corridor (%.2f–%.2f) · %d lower + %d upper touches · %.0f%% contained\n".format(
            widthPercent, lower, upper, lowerTouches, upperTouches, contained * 100.0
        ) + "Price is %.0f%% through the corridor · %.2f%% remains to the upper edge\n".format(
            position * 100.0, remaining
        ) + "Opportunity is timing relevance, not profit probability."
        return ShortMove(
            symbol = symbol,
            changePercent = remaining,
            open = latest.close,
            close = upper,
            startedAtEpochSeconds = session.first().minuteEpochSeconds,
            endedAtEpochSeconds = latest.minuteEpochSeconds,
            barCount = session.size,
            pattern = ShortMovePattern.TRADABLE_CORRIDOR,
            eventEpochSeconds = latest.minuteEpochSeconds,
            opportunityScore = score,
            opportunityDetails = details,
            corridorLower = lower,
            corridorUpper = upper
        )
    }

    private fun touchGroups(bars: List<MinuteBar>, predicate: (MinuteBar) -> Boolean): Int {
        var groups = 0
        var touching = false
        bars.forEach { bar ->
            val current = predicate(bar)
            if (current && !touching) groups++
            touching = current
        }
        return groups
    }

    private const val LOOKBACK_MINUTES = 120
    private const val MIN_BARS = 45
    private const val MAX_DATA_AGE_MINUTES = 3L

    // Keep 76% central coverage, biased toward support so brief upside spikes do not lift
    // a long-entry corridor as strongly as downside observations affect its safety boundary.
    private const val LOWER_QUANTILE = 0.08
    private const val UPPER_QUANTILE = 0.84
    private const val MIN_WIDTH_PERCENT = 0.65
    private const val MAX_WIDTH_PERCENT = 6.0
    private const val WIDTH_FULL_SCORE_PERCENT = 1.50
    private const val EDGE_TOLERANCE_SHARE = 0.12
    private const val MIN_TOUCHES_PER_EDGE = 2
    private const val MIN_CONTAINMENT = 0.82
    private const val MAX_NORMALIZED_DRIFT = 0.90
    private const val RECENT_STABILITY_BARS = 12
    private const val MAX_RECENT_BREAKS = 1
    private const val MIN_REMAINING_PERCENT = 0.25
    private const val MIN_OPPORTUNITY_SCORE = 35
}