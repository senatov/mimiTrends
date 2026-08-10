package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.isValidMinuteBar
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Finds a confirmed base and early rebound before it becomes a mature steady trend. */
internal class EarlyRecoveryDetector(private val zoneOverride: ZoneId? = null) {
    fun rejectionReason(bars: List<MinuteBar>): String {
        val session = currentSession(bars)
        if (session.size < MIN_SESSION_BARS) return "INSUFFICIENT_SESSION"
        val context = session.takeLast(CONTEXT_MINUTES)
        val lowIndex = context.indices.minByOrNull { context[it].low } ?: return "NO_SESSION_LOW"
        val recoveryBars = context.drop(lowIndex)
        if (lowIndex < MIN_DECLINE_BARS) return "NO_INTRADAY_DECLINE"
        if (recoveryBars.size !in MIN_RECOVERY_BARS..MAX_RECOVERY_BARS) return "RECOVERY_NOT_FORMED"
        val referenceHigh = context.take(lowIndex + 1).takeLast(DECLINE_LOOKBACK_BARS).maxOf(MinuteBar::high)
        val decline = -percent(referenceHigh, context[lowIndex].low)
        val recovery = percent(context[lowIndex].low, recoveryBars.last().close)
        if (decline < MIN_DECLINE_PERCENT) return "NO_MATERIAL_DECLINE"
        if (recovery < MIN_RECOVERY_PERCENT || recovery / decline < MIN_RECOVERY_RATIO) return "RECOVERY_TOO_SMALL"
        if (!hasHigherLows(recoveryBars)) return "NO_HIGHER_LOW"
        if (!hasPositiveRecentSlope(recoveryBars) || recentReturn(recoveryBars) < MIN_RECENT_RETURN_PERCENT) {
            return "WEAK_LAST_10M"
        }
        if (retracementFromRecoveryHigh(recoveryBars) > recovery * MAX_RETRACE_SHARE) return "RECOVERY_RETRACE"
        return "NO_CURRENT_SIGNAL"
    }

    fun detect(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        val session = currentSession(bars)
        if (session.size < MIN_SESSION_BARS) return null
        val context = session.takeLast(CONTEXT_MINUTES)
        val lowIndex = context.indices.minByOrNull { context[it].low } ?: return null
        val recoveryBars = context.drop(lowIndex)
        if (lowIndex < MIN_DECLINE_BARS || recoveryBars.size !in MIN_RECOVERY_BARS..MAX_RECOVERY_BARS) return null

        val preLow = context.take(lowIndex + 1).takeLast(DECLINE_LOOKBACK_BARS)
        val referenceHigh = preLow.maxOf(MinuteBar::high)
        val sessionLow = context[lowIndex].low
        val decline = -percent(referenceHigh, sessionLow)
        val recovery = percent(sessionLow, recoveryBars.last().close)
        if (decline < MIN_DECLINE_PERCENT || recovery < MIN_RECOVERY_PERCENT) return null
        val recoveryRatio = recovery / decline
        if (recoveryRatio < MIN_RECOVERY_RATIO) return null
        if (!hasHigherLows(recoveryBars) || !hasPositiveRecentSlope(recoveryBars)) return null
        if (recentReturn(recoveryBars) < MIN_RECENT_RETURN_PERCENT) return null
        if (retracementFromRecoveryHigh(recoveryBars) > recovery * MAX_RETRACE_SHARE) return null

        val turnover = session.sumOf { it.close * it.volume }
        val latest = recoveryBars.last()
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        val efficiency = pathEfficiency(recoveryBars)
        val score = 1.50 + recovery * 0.85 + recoveryRatio * 1.50 + efficiency
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = score,
            priceAnomaly = decline,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = efficiency,
            windowChangePercent = recentReturn(recoveryBars),
            windowVolume = recoveryBars.sumOf(MinuteBar::volume),
            sessionVolume = session.sumOf(MinuteBar::volume),
            sessionTurnover = turnover,
            signalAgeMinutes = 0,
            signalSource = "Early recovery ↑ · watch",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000L,
            signalWindowLabel = "${recoveryBars.size - 1}m recovery",
            signalPrice = latest.close,
            signalEpochMillis = latest.minuteEpochSeconds * 1_000L
        )
    }

    private fun currentSession(bars: List<MinuteBar>): List<MinuteBar> {
        val sorted = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = sorted.lastOrNull() ?: return emptyList()
        val date = local(latest).toLocalDate()
        return sorted.filter { local(it).toLocalDate() == date }
    }

    private fun hasHigherLows(bars: List<MinuteBar>): Boolean {
        if (bars.size < MIN_RECOVERY_BARS) return false
        val thirds = bars.drop(1).chunked((bars.size / 3).coerceAtLeast(2)).takeLast(3)
        if (thirds.size < 2) return false
        val lows = thirds.map { part -> part.minOf(MinuteBar::low) }
        return lows.zipWithNext().all { (first, second) -> second >= first * (1.0 - LOW_TOLERANCE_PERCENT / 100.0) } &&
            lows.last() > lows.first()
    }

    private fun hasPositiveRecentSlope(bars: List<MinuteBar>): Boolean {
        val recent = bars.takeLast(RECENT_MINUTES)
        if (recent.size < MIN_RECENT_BARS) return false
        val meanX = (recent.lastIndex / 2.0)
        val meanY = recent.map(MinuteBar::close).average()
        return recent.indices.sumOf { index -> (index - meanX) * (recent[index].close - meanY) } > 0.0
    }

    private fun recentReturn(bars: List<MinuteBar>): Double {
        val latest = bars.last()
        val cutoff = latest.minuteEpochSeconds - RECENT_MINUTES * 60L
        val recent = bars.dropWhile { it.minuteEpochSeconds < cutoff }
        return percent(recent.first().close, recent.last().close)
    }

    private fun retracementFromRecoveryHigh(bars: List<MinuteBar>): Double =
        -percent(bars.maxOf(MinuteBar::high), bars.last().close)

    private fun pathEfficiency(bars: List<MinuteBar>): Double {
        val changes = bars.zipWithNext { first, second -> percent(first.close, second.close) }
        val path = changes.sumOf(::abs)
        return if (path > 0.0) (percent(bars.first().close, bars.last().close) / path).coerceIn(0.0, 1.0) else 0.0
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private companion object {
        const val CONTEXT_MINUTES = 120
        const val MIN_SESSION_BARS = 25
        const val DECLINE_LOOKBACK_BARS = 30
        const val MIN_DECLINE_BARS = 4
        const val MIN_RECOVERY_BARS = 10
        const val MAX_RECOVERY_BARS = 75
        const val MIN_DECLINE_PERCENT = 0.75
        const val MIN_RECOVERY_PERCENT = 0.35
        const val MIN_RECOVERY_RATIO = 0.25
        const val RECENT_MINUTES = 10
        const val MIN_RECENT_BARS = 6
        const val MIN_RECENT_RETURN_PERCENT = 0.12
        const val LOW_TOLERANCE_PERCENT = 0.08
        const val MAX_RETRACE_SHARE = 0.45
    }
}
