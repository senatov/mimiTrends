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

/** Surfaces exceptional selloffs for observation without claiming that a bottom has formed. */
internal class OversoldWatchDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        val sorted = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = sorted.lastOrNull() ?: return null
        val session = sorted.filter { local(it).toLocalDate() == local(latest).toLocalDate() }
        val context = session.takeLast(LOOKBACK_BARS)
        if (context.size < MIN_CONTEXT_BARS) return null

        val peakIndex = context.indices.maxByOrNull { context[it].high } ?: return null
        if (peakIndex > context.lastIndex - MIN_DECLINE_BARS) return null
        val declineBars = context.drop(peakIndex)
        val peak = context[peakIndex].high
        val decline = -percent(peak, latest.close)
        if (decline < max(MIN_DECLINE_PERCENT, criteria.minAbsoluteMovePercent * DECLINE_CRITERIA_MULTIPLIER)) return null
        val distanceFromLow = percent(context.minOf(MinuteBar::low), latest.close)
        if (distanceFromLow > max(MAX_DISTANCE_FROM_LOW_PERCENT, decline * MAX_DISTANCE_SHARE)) return null

        val changes = declineBars.zipWithNext { first, second -> percent(first.close, second.close) }
        if (changes.count { it < 0.0 } < MIN_NEGATIVE_STEPS) return null
        val path = changes.sumOf(::abs)
        val efficiency = if (path > 0.0) decline / path else 0.0
        if (efficiency < MIN_DECLINE_EFFICIENCY) return null
        if (changes.maxOf(::abs) > decline * MAX_SINGLE_STEP_SHARE) return null

        val turnover = session.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        val score = 2.0 + decline * 0.65 + efficiency
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = score,
            priceAnomaly = decline,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = efficiency,
            windowChangePercent = -decline,
            windowVolume = declineBars.sumOf(MinuteBar::volume),
            sessionVolume = session.sumOf(MinuteBar::volume),
            sessionTurnover = turnover,
            signalAgeMinutes = 0,
            signalSource = "Oversold decline ↓ · watch · bottom unconfirmed",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = "${declineBars.size - 1}m selloff",
            signalPrice = latest.close,
            signalEpochMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private companion object {
        const val LOOKBACK_BARS = 45
        const val MIN_CONTEXT_BARS = 20
        const val MIN_DECLINE_BARS = 4
        const val MIN_DECLINE_PERCENT = 2.00
        const val DECLINE_CRITERIA_MULTIPLIER = 6.0
        const val MAX_DISTANCE_FROM_LOW_PERCENT = 0.35
        const val MAX_DISTANCE_SHARE = 0.12
        const val MIN_NEGATIVE_STEPS = 3
        const val MIN_DECLINE_EFFICIENCY = 0.35
        const val MAX_SINGLE_STEP_SHARE = 0.80
    }
}
