package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

/** Rejects long candidates whose most recent price structure is already failing. */
internal class MomentumHealthFilter(private val zoneOverride: ZoneId? = null) {
    fun isHealthy(bars: List<MinuteBar>): Boolean {
        val latest = bars.lastOrNull() ?: return false
        val session = bars.filter { local(it).toLocalDate() == local(latest).toLocalDate() }
        val recent = session.filter {
            it.minuteEpochSeconds >= latest.minuteEpochSeconds - LOOKBACK_MINUTES * 60L
        }
        if (recent.size < MIN_RECENT_BARS) return true

        val tail = recent.takeLast(TAIL_BARS)
        val preceding = recent.dropLast(TAIL_BARS).takeLast(BASELINE_BARS)
        val tailReturn = percent(tail.first().close, tail.last().close)
        val tailSlope = normalizedSlope(tail)
        val precedingSlope = normalizedSlope(preceding)
        val typicalRange = recent.map { percent(it.low, it.high) }.sorted().let { it[it.size / 2] }
        val drawdown = -percent(recent.maxOf(MinuteBar::high), latest.close)

        if (hasPreBreakoutWhipsaw(recent)) return false
        if (drawdown >= max(MIN_DRAWDOWN_PERCENT, typicalRange * MAX_RANGE_DRAWDOWN_MULTIPLIER)) return false
        if (tailReturn <= -MAX_TAIL_LOSS_PERCENT || tailSlope <= -MAX_NEGATIVE_SLOPE_PERCENT) return false
        if (precedingSlope >= MIN_POSITIVE_BASELINE_SLOPE_PERCENT &&
            tailSlope <= precedingSlope * MIN_SLOPE_RETENTION && tailReturn <= MAX_STALLED_RETURN_PERCENT) return false
        return !hasLowerStructure(tail)
    }

    private fun hasPreBreakoutWhipsaw(recent: List<MinuteBar>): Boolean {
        val context = recent.dropLast(BREAKOUT_BARS).takeLast(CHOP_BARS)
        if (context.size < CHOP_BARS) return false
        val changes = context.zipWithNext { first, second -> percent(first.close, second.close) }
        val meaningful = changes.filter { abs(it) >= MIN_DIRECTION_CHANGE_PERCENT }
        val directionChanges = meaningful.zipWithNext().count { (first, second) -> first * second < 0.0 }
        val path = changes.sumOf(::abs)
        val net = abs(percent(context.first().close, context.last().close))
        val efficiency = if (path > 0.0) net / path else 1.0
        val range = percent(context.minOf(MinuteBar::low), context.maxOf(MinuteBar::high))
        return directionChanges >= MIN_DIRECTION_CHANGES && path >= MIN_CHOP_PATH_PERCENT &&
            range >= MIN_CHOP_RANGE_PERCENT && efficiency <= MAX_CHOP_EFFICIENCY
    }

    private fun hasLowerStructure(bars: List<MinuteBar>): Boolean {
        if (bars.size < TAIL_BARS) return false
        val middle = bars.size / 2
        val earlier = bars.take(middle)
        val later = bars.drop(middle)
        return later.maxOf(MinuteBar::high) < earlier.maxOf(MinuteBar::high) &&
            later.minOf(MinuteBar::low) < earlier.minOf(MinuteBar::low) &&
            percent(bars.first().close, bars.last().close) <= MAX_STALLED_RETURN_PERCENT
    }

    private fun normalizedSlope(bars: List<MinuteBar>): Double {
        if (bars.size < 2) return 0.0
        val firstEpoch = bars.first().minuteEpochSeconds
        val x = bars.map { (it.minuteEpochSeconds - firstEpoch) / 60.0 }
        val meanX = x.average()
        val meanY = bars.map(MinuteBar::close).average()
        val denominator = x.sumOf { (it - meanX) * (it - meanX) }
        if (denominator <= 0.0 || meanY <= 0.0) return 0.0
        val slope = bars.indices.sumOf { index -> (x[index] - meanX) * (bars[index].close - meanY) } / denominator
        return slope / meanY * 100.0
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private companion object {
        const val LOOKBACK_MINUTES = 20
        const val MIN_RECENT_BARS = 10
        const val TAIL_BARS = 5
        const val BASELINE_BARS = 10
        const val BREAKOUT_BARS = 2
        const val CHOP_BARS = 8
        const val MIN_DRAWDOWN_PERCENT = 0.25
        const val MAX_RANGE_DRAWDOWN_MULTIPLIER = 2.0
        const val MAX_TAIL_LOSS_PERCENT = 0.10
        const val MAX_NEGATIVE_SLOPE_PERCENT = 0.025
        const val MIN_POSITIVE_BASELINE_SLOPE_PERCENT = 0.01
        const val MIN_SLOPE_RETENTION = 0.20
        const val MAX_STALLED_RETURN_PERCENT = 0.01
        const val MIN_DIRECTION_CHANGE_PERCENT = 0.03
        const val MIN_DIRECTION_CHANGES = 3
        const val MIN_CHOP_PATH_PERCENT = 0.40
        const val MIN_CHOP_RANGE_PERCENT = 0.25
        const val MAX_CHOP_EFFICIENCY = 0.35
    }
}
