package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.isValidMinuteBar
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Detects a positive overnight gap that is holding and continuing after the open. */
internal class GapContinuationDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        val sessions = bars.asSequence().filter(MinuteBar::isValidMinuteBar)
            .sortedBy(MinuteBar::minuteEpochSeconds).groupBy { local(it).toLocalDate() }.values.toList()
        if (sessions.size < 2) return null
        val previous = sessions[sessions.lastIndex - 1]
        val current = sessions.last()
        if (current.size < MIN_CURRENT_BARS) return null
        val previousClose = previous.last().close
        val open = current.first().open
        val latest = current.last()
        val gapPercent = percent(previousClose, open)
        if (gapPercent < MIN_GAP_PERCENT) return null
        val sessionReturn = percent(open, latest.close)
        val retainedGap = percent(previousClose, latest.close)
        if (sessionReturn < MIN_CONTINUATION_PERCENT || retainedGap < gapPercent * MIN_GAP_RETENTION) return null
        val recent = current.takeLast(RECENT_BARS)
        if (recent.size < MIN_RECENT_BARS || percent(recent.first().close, recent.last().close) < MIN_RECENT_RETURN) return null
        val sessionHigh = current.maxOf(MinuteBar::high)
        val highDistance = -percent(sessionHigh, latest.close)
        if (highDistance > MAX_DISTANCE_FROM_HIGH_PERCENT) return null
        val turnover = current.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        val path = current.zipWithNext { first, second -> abs(percent(first.close, second.close)) }.sum()
        val efficiency = (sessionReturn / path.coerceAtLeast(sessionReturn)).coerceIn(0.0, 1.0)
        val score = 3.0 + gapPercent.coerceAtMost(10.0) * 0.45 +
            sessionReturn.coerceAtMost(5.0) * 0.80 + efficiency
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = score,
            priceAnomaly = gapPercent,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = efficiency,
            windowChangePercent = retainedGap,
            windowVolume = current.sumOf(MinuteBar::volume),
            sessionVolume = current.sumOf(MinuteBar::volume),
            sessionTurnover = turnover,
            signalAgeMinutes = 0,
            signalSource = "Gap-and-go ↑",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = "${"%.1f".format(gapPercent)}% opening gap",
            signalPrice = latest.close,
            signalEpochMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private companion object {
        const val MIN_GAP_PERCENT = 1.5
        const val MIN_GAP_RETENTION = 0.70
        const val MIN_CONTINUATION_PERCENT = 0.15
        const val MIN_CURRENT_BARS = 8
        const val RECENT_BARS = 8
        const val MIN_RECENT_BARS = 5
        const val MIN_RECENT_RETURN = -0.10
        const val MAX_DISTANCE_FROM_HIGH_PERCENT = 0.80
    }
}
