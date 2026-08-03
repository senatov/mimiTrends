package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

internal data class VReversal(
    val direction: Int,
    val recoveryPercent: Double,
    val shockPercent: Double,
    val shockZ: Double,
    val recoveryRatio: Double,
    val volume: Double,
    val score: Double,
    val latestBar: MinuteBar,
    val extremeAgeMinutes: Int
)

private data class VPattern(
    val direction: Int,
    val shockPercent: Double,
    val recoveryPercent: Double,
    val recoveryEfficiency: Double,
    val extremeAgeMinutes: Int,
    val volume: Double,
    val latest: MinuteBar
)

private const val PATTERN_BARS = 11
private const val MIN_PATTERN_BARS = 5
private const val MAX_EXTREME_AGE_MINUTES = 9
private const val SHOCK_WINDOW_BARS = 3
private const val MIN_SHOCK_PERCENT = 0.25
private const val MIN_RECOVERY_PERCENT = 0.20
private const val MIN_RECOVERY_EFFICIENCY = 0.50
private const val MIN_DIRECTIONAL_STEPS = 2
private const val MIN_QUALITY = 0.70
private const val TIME_RADIUS_MINUTES = 15L
private const val MIN_SESSIONS = 3
private const val MAX_SESSIONS = 20
private const val MIN_BASELINE = 15
private const val RETURN_FLOOR = 0.015

internal class VReversalDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(bars: List<MinuteBar>, criteria: ScannerCriteria): VReversal? {
        val recent = sameSessionTail(bars)
        if (recent.size < MIN_PATTERN_BARS) return null
        val bullish = bullishPattern(recent, criteria)
        val bearish = bearishPattern(recent, criteria)
        val pattern = listOfNotNull(bullish, bearish).maxByOrNull { it.shockPercent } ?: return null
        val shockZ = shockZ(bars, pattern, criteria) ?: return null
        if (shockZ < criteria.minJumpZ) return null
        val recoveryRatio = (pattern.recoveryPercent / pattern.shockPercent).coerceIn(0.0, 1.5)
        val score = (0.65 * shockZ + 0.50 * recoveryRatio) *
            (MIN_QUALITY + (1.0 - MIN_QUALITY) * pattern.recoveryEfficiency)
        return VReversal(pattern.direction, pattern.recoveryPercent * pattern.direction,
            pattern.shockPercent, shockZ, recoveryRatio, pattern.volume, score,
            recent.last(), pattern.extremeAgeMinutes)
    }

    private fun bullishPattern(bars: List<MinuteBar>, criteria: ScannerCriteria): VPattern? {
        val extremeIndex = bars.indices.minByOrNull { bars[it].low } ?: return null
        if (extremeIndex !in 1 until bars.lastIndex) return null
        val age = bars.lastIndex - extremeIndex
        if (age > MAX_EXTREME_AGE_MINUTES) return null
        val reference = bars.subList((extremeIndex - SHOCK_WINDOW_BARS).coerceAtLeast(0), extremeIndex)
            .maxOf { it.high }
        val extreme = bars[extremeIndex].low
        val shock = declinePercent(reference, extreme)
        val recovery = risePercent(extreme, bars.last().close)
        return pattern(bars, extremeIndex, 1, shock, recovery, age, criteria)
    }

    private fun bearishPattern(bars: List<MinuteBar>, criteria: ScannerCriteria): VPattern? {
        val extremeIndex = bars.indices.maxByOrNull { bars[it].high } ?: return null
        if (extremeIndex !in 1 until bars.lastIndex) return null
        val age = bars.lastIndex - extremeIndex
        if (age > MAX_EXTREME_AGE_MINUTES) return null
        val reference = bars.subList((extremeIndex - SHOCK_WINDOW_BARS).coerceAtLeast(0), extremeIndex)
            .minOf { it.low }
        val extreme = bars[extremeIndex].high
        val shock = risePercent(reference, extreme)
        val recovery = declinePercent(extreme, bars.last().close)
        return pattern(bars, extremeIndex, -1, shock, recovery, age, criteria)
    }

    private fun pattern(
        bars: List<MinuteBar>,
        extremeIndex: Int,
        direction: Int,
        shock: Double,
        recovery: Double,
        age: Int,
        criteria: ScannerCriteria
    ): VPattern? {
        val minimumShock = max(MIN_SHOCK_PERCENT, criteria.minAbsoluteMovePercent * 1.25)
        val minimumRecovery = max(MIN_RECOVERY_PERCENT, criteria.minAbsoluteMovePercent)
        if (shock < minimumShock || recovery < minimumRecovery) return null
        val recoveryBars = bars.subList(extremeIndex, bars.size)
        val prices = if (direction > 0) listOf(bars[extremeIndex].low) + recoveryBars.map { it.close }
            else listOf(bars[extremeIndex].high) + recoveryBars.map { it.close }
        val signedChanges = prices.zipWithNext { first, second -> percent(first, second) * direction }
        val path = signedChanges.sumOf { abs(it) }
        val efficiency = if (path > 0.0) recovery / path else 0.0
        if (efficiency < MIN_RECOVERY_EFFICIENCY || (signedChanges.lastOrNull() ?: 0.0) <= 0.0) return null
        if (signedChanges.count { it > 0.0 } < MIN_DIRECTIONAL_STEPS) return null
        return VPattern(direction, shock, recovery, efficiency, age, bars.sumOf { it.volume }, bars.last())
    }

    private fun shockZ(bars: List<MinuteBar>, pattern: VPattern, criteria: ScannerCriteria): Double? {
        val latestDate = local(pattern.latest).toLocalDate()
        val latestTime = local(pattern.latest).toLocalTime()
        val historical = bars.windowed(SHOCK_WINDOW_BARS).mapNotNull { sample ->
            if (!continuous(sample)) return@mapNotNull null
            val end = sample.last()
            val date = local(end).toLocalDate()
            if (date == latestDate || abs(java.time.Duration.between(local(end).toLocalTime(), latestTime).toMinutes()) > TIME_RADIUS_MINUTES) {
                return@mapNotNull null
            }
            date to abs(percent(sample.first().open, sample.last().close))
        }
        val dates = historical.asReversed().map { it.first }.distinct()
            .take(criteria.baselineSessions.coerceIn(MIN_SESSIONS, MAX_SESSIONS)).toSet()
        val baseline = historical.filter { it.first in dates }.map { it.second }
        if (dates.size < MIN_SESSIONS || baseline.size < MIN_BASELINE) return null
        return max(0.0, pattern.shockPercent - median(baseline)) / robustScale(baseline)
    }

    private fun sameSessionTail(bars: List<MinuteBar>): List<MinuteBar> {
        val latest = bars.lastOrNull() ?: return emptyList()
        val date = local(latest).toLocalDate()
        return bars.asReversed().takeWhile { local(it).toLocalDate() == date }
            .take(PATTERN_BARS).asReversed()
    }

    private fun continuous(bars: List<MinuteBar>) = bars.zipWithNext().all { (first, second) ->
        second.minuteEpochSeconds - first.minuteEpochSeconds == 60L && local(first).toLocalDate() == local(second).toLocalDate()
    }

    private fun robustScale(values: List<Double>): Double {
        val center = median(values)
        val mad = median(values.map { abs(it - center) })
        return max(1.4826 * mad, max(center * 0.20, RETURN_FLOOR))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))
    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0
    private fun risePercent(low: Double, last: Double) = max(0.0, percent(low, last))
    private fun declinePercent(high: Double, last: Double) = max(0.0, -percent(high, last))

}
