package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.statistics.ValidatedStatistics
import org.senatov.mimitrends.model.ScannerCriteria
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
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
    val shockEfficiency: Double,
    val recoveryEfficiency: Double,
    val velocityRatio: Double,
    val extremeAgeMinutes: Int,
    val shockBars: Int,
    val volume: Double,
    val latest: MinuteBar
)

internal class VReversalDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(bars: List<MinuteBar>, criteria: ScannerCriteria): VReversal? {
        val recent = sameSessionTail(bars)
        if (recent.size < MIN_PATTERN_BARS || !continuous(recent)) return null
        return patterns(recent, criteria)
            .filter { pattern -> pattern.direction < 0 || recent.size >= MIN_BULLISH_STRUCTURE_BARS }
            .mapNotNull { pattern -> score(bars, pattern, criteria) }
            .maxByOrNull { it.score }
    }

    private fun patterns(bars: List<MinuteBar>, criteria: ScannerCriteria): List<VPattern> = buildList {
        for (extremeIndex in 1 until bars.lastIndex) {
            val age = bars.lastIndex - extremeIndex
            if (age > MAX_EXTREME_AGE_MINUTES) continue
            val maxShockBars = minOf(MAX_SHOCK_BARS, extremeIndex)
            for (shockBars in MIN_SHOCK_BARS..maxShockBars) {
                bullishPattern(bars, extremeIndex, shockBars, criteria)?.let(::add)
                bearishPattern(bars, extremeIndex, shockBars, criteria)?.let(::add)
            }
        }
    }

    private fun bullishPattern(
        bars: List<MinuteBar>, extremeIndex: Int, shockBars: Int, criteria: ScannerCriteria
    ): VPattern? {
        val shockSlice = bars.subList(extremeIndex - shockBars, extremeIndex + 1)
        if (bars[extremeIndex].low > shockSlice.minOf { it.low }) return null
        val reference = shockSlice.first().high
        val extreme = bars[extremeIndex].low
        return pattern(bars, extremeIndex, shockBars, 1, declinePercent(reference, extreme),
            risePercent(extreme, bars.last().close), criteria)
    }

    private fun bearishPattern(
        bars: List<MinuteBar>, extremeIndex: Int, shockBars: Int, criteria: ScannerCriteria
    ): VPattern? {
        val shockSlice = bars.subList(extremeIndex - shockBars, extremeIndex + 1)
        if (bars[extremeIndex].high < shockSlice.maxOf { it.high }) return null
        val reference = shockSlice.first().low
        val extreme = bars[extremeIndex].high
        return pattern(bars, extremeIndex, shockBars, -1, risePercent(reference, extreme),
            declinePercent(extreme, bars.last().close), criteria)
    }

    private fun pattern(
        bars: List<MinuteBar>, extremeIndex: Int, shockBars: Int, direction: Int,
        shock: Double, recovery: Double, criteria: ScannerCriteria
    ): VPattern? {
        val minimumShock = max(MIN_SHOCK_PERCENT, criteria.minAbsoluteMovePercent * 1.25)
        val minimumRecovery = max(MIN_RECOVERY_PERCENT, criteria.minAbsoluteMovePercent)
        if (shock < minimumShock || recovery < minimumRecovery) return null
        val shockPrices = bars.subList(extremeIndex - shockBars, extremeIndex + 1).map { it.close }
        val shockChanges = shockPrices.zipWithNext { first, second -> -percent(first, second) * direction }
        val shockPath = shockChanges.sumOf { abs(it) }
        val shockEfficiency = if (shockPath > 0.0) shock / shockPath else 0.0
        if (shockEfficiency < MIN_SHOCK_EFFICIENCY) return null
        val recoveryBars = bars.subList(extremeIndex, bars.size)
        val extreme = if (direction > 0) bars[extremeIndex].low else bars[extremeIndex].high
        val recoveryPrices = listOf(extreme) + recoveryBars.map { it.close }
        val recoveryChanges = recoveryPrices.zipWithNext { first, second -> percent(first, second) * direction }
        val recoveryPath = recoveryChanges.sumOf { abs(it) }
        val recoveryEfficiency = if (recoveryPath > 0.0) recovery / recoveryPath else 0.0
        if (recoveryEfficiency < MIN_RECOVERY_EFFICIENCY) return null
        if (recoveryChanges.count { it > 0.0 } < MIN_DIRECTIONAL_STEPS) return null
        if ((recoveryChanges.lastOrNull() ?: 0.0) <= 0.0) return null
        val age = bars.lastIndex - extremeIndex
        val activeShockBars = shockChanges.count { it > ACTIVE_SHOCK_STEP_PERCENT }.coerceAtLeast(1)
        val velocityRatio = (recovery / age.coerceAtLeast(1)) / (shock / activeShockBars)
        if (velocityRatio < MIN_VELOCITY_RATIO) return null
        if (bottomTouches(recoveryBars, extreme, shock, direction) > MAX_BOTTOM_TOUCHES) return null
        return VPattern(direction, shock, recovery, shockEfficiency, recoveryEfficiency, velocityRatio,
            age, shockBars, bars.sumOf { it.volume }, bars.last())
    }

    private fun bottomTouches(bars: List<MinuteBar>, extreme: Double, shock: Double, direction: Int): Int {
        val tolerance = extreme * shock / 100.0 * BOTTOM_ZONE_SHARE
        return bars.drop(1).count { bar ->
            if (direction > 0) bar.low <= extreme + tolerance else bar.high >= extreme - tolerance
        }
    }

    private fun score(bars: List<MinuteBar>, pattern: VPattern, criteria: ScannerCriteria): VReversal? {
        val shockZ = shockZ(bars, pattern, criteria) ?: return null
        if (shockZ < criteria.minJumpZ) return null
        val recoveryRatio = (pattern.recoveryPercent / pattern.shockPercent).coerceIn(0.0, 1.5)
        if (opposesDescendingStructure(bars, pattern) && !breaksOpposingStructure(bars, pattern)) return null
        val quality = MIN_QUALITY + (1.0 - MIN_QUALITY) * pattern.recoveryEfficiency
        val freshness = exp(-ln(2.0) * pattern.extremeAgeMinutes / FRESHNESS_HALF_LIFE_MINUTES)
        val raw = 0.58 * shockZ + 0.80 * recoveryRatio +
            0.35 * pattern.velocityRatio.coerceAtMost(2.0) + 0.30 * pattern.shockEfficiency.coerceAtMost(1.0)
        return VReversal(pattern.direction, pattern.recoveryPercent * pattern.direction,
            pattern.shockPercent, shockZ, recoveryRatio, pattern.volume, raw * quality * freshness,
            pattern.latest, pattern.extremeAgeMinutes)
    }

    private fun opposesDescendingStructure(bars: List<MinuteBar>, pattern: VPattern): Boolean {
        val latestEpoch = pattern.latest.minuteEpochSeconds
        val context = bars.filter {
            local(it).toLocalDate() == local(pattern.latest).toLocalDate() &&
                it.minuteEpochSeconds >= latestEpoch - STRUCTURE_MINUTES * 60L
        }
        if (context.size < MIN_STRUCTURE_BARS) return false
        val midpoint = context.size / 2
        val earlier = context.take(midpoint)
        val later = context.drop(midpoint)
        val directionalReturn = percent(context.first().close, context.last().close) * pattern.direction
        val opposingHighs = if (pattern.direction > 0) later.maxOf { it.high } < earlier.maxOf { it.high }
            else later.minOf { it.low } > earlier.minOf { it.low }
        val opposingLows = if (pattern.direction > 0) later.minOf { it.low } < earlier.minOf { it.low }
            else later.maxOf { it.high } > earlier.maxOf { it.high }
        return directionalReturn < -MIN_OPPOSING_RETURN_PERCENT && opposingHighs && opposingLows
    }

    private fun breaksOpposingStructure(bars: List<MinuteBar>, pattern: VPattern): Boolean {
        val session = bars.filter { local(it).toLocalDate() == local(pattern.latest).toLocalDate() }
        val beforeExtreme = session.dropLast(pattern.extremeAgeMinutes + 1).takeLast(SWING_LOOKBACK_BARS)
        if (beforeExtreme.size < MIN_SWING_BARS) return false
        return if (pattern.direction > 0) pattern.latest.close > beforeExtreme.maxOf { it.close }
        else pattern.latest.close < beforeExtreme.minOf { it.close }
    }

    private fun shockZ(bars: List<MinuteBar>, pattern: VPattern, criteria: ScannerCriteria): Double? {
        val latestDate = local(pattern.latest).toLocalDate()
        val latestTime = local(pattern.latest).toLocalTime()
        val sampleSize = pattern.shockBars + 1
        val historical = bars.windowed(sampleSize).mapNotNull { sample ->
            if (!continuous(sample)) return@mapNotNull null
            val end = sample.last()
            val date = local(end).toLocalDate()
            val minutes = abs(java.time.Duration.between(local(end).toLocalTime(), latestTime).toMinutes())
            if (date == latestDate || minutes > TIME_RADIUS_MINUTES) return@mapNotNull null
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
        second.minuteEpochSeconds - first.minuteEpochSeconds == 60L &&
            local(first).toLocalDate() == local(second).toLocalDate()
    }

    private fun robustScale(values: List<Double>): Double {
        val center = ValidatedStatistics.median(values)
        val mad = ValidatedStatistics.median(values.map { abs(it - center) })
        return max(1.4826 * mad, max(center * 0.20, RETURN_FLOOR))
    }

    private fun median(values: List<Double>) = ValidatedStatistics.median(values)

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))
    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0
    private fun risePercent(low: Double, last: Double) = max(0.0, percent(low, last))
    private fun declinePercent(high: Double, last: Double) = max(0.0, -percent(high, last))

    private companion object {
        const val PATTERN_BARS = 30
        const val MIN_PATTERN_BARS = 5
        const val MIN_BULLISH_STRUCTURE_BARS = 20
        const val MAX_EXTREME_AGE_MINUTES = 9
        const val MIN_SHOCK_BARS = 2
        const val MAX_SHOCK_BARS = 6
        const val MIN_SHOCK_PERCENT = 0.25
        const val MIN_RECOVERY_PERCENT = 0.20
        const val MIN_SHOCK_EFFICIENCY = 0.45
        const val MIN_RECOVERY_EFFICIENCY = 0.50
        const val MIN_DIRECTIONAL_STEPS = 2
        const val MIN_VELOCITY_RATIO = 0.35
        const val ACTIVE_SHOCK_STEP_PERCENT = 0.01
        const val BOTTOM_ZONE_SHARE = 0.15
        const val MAX_BOTTOM_TOUCHES = 3
        const val MIN_QUALITY = 0.70
        const val FRESHNESS_HALF_LIFE_MINUTES = 8.0
        const val TIME_RADIUS_MINUTES = 20L
        const val MIN_SESSIONS = 3
        const val MAX_SESSIONS = 20
        const val MIN_BASELINE = 15
        const val RETURN_FLOOR = 0.015
        const val STRUCTURE_MINUTES = 30
        const val MIN_STRUCTURE_BARS = 20
        const val MIN_OPPOSING_RETURN_PERCENT = 0.25
        const val SWING_LOOKBACK_BARS = 12
        const val MIN_SWING_BARS = 6
    }
}
