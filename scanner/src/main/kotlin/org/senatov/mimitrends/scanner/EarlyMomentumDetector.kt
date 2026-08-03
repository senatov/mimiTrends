package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.max

internal class EarlyMomentumDetector(private val zoneOverride: ZoneId? = null) {
    fun detect(bars: List<MinuteBar>, criteria: ScannerCriteria): EarlyMomentum? {
        val currentBars = bars.takeLast(WINDOW_BARS)
        if (!isContinuous(currentBars)) return null
        val current = window(currentBars) ?: return null
        val minimumMove = max(MIN_MOVE_PERCENT, criteria.minAbsoluteMovePercent * 1.5)
        if (abs(current.returnPercent) < minimumMove || current.efficiency < MIN_EFFICIENCY) return null
        if (!isDirectionallyConsistent(currentBars, current.returnPercent)) return null

        val latest = currentBars.last()
        val latestDate = local(latest).toLocalDate()
        val latestTime = local(latest).toLocalTime()
        val historical = bars.windowed(WINDOW_BARS).mapNotNull { sample ->
            if (!isContinuous(sample)) return@mapNotNull null
            val end = sample.last()
            val date = local(end).toLocalDate()
            if (date == latestDate || abs(java.time.Duration.between(local(end).toLocalTime(), latestTime).toMinutes()) > TIME_RADIUS_MINUTES) {
                return@mapNotNull null
            }
            window(sample)
        }
        val selectedDates = historical.asReversed().map(EarlyWindow::date).distinct()
            .take(criteria.baselineSessions.coerceIn(MIN_SESSIONS, MAX_SESSIONS)).toSet()
        val baseline = historical.filter { it.date in selectedDates }
        if (selectedDates.size < MIN_SESSIONS || baseline.size < MIN_BASELINE) return null

        val returns = baseline.map(EarlyWindow::returnPercent)
        val jumpZ = abs(current.returnPercent - median(returns)) / robustScale(returns, RETURN_FLOOR)
        if (jumpZ < criteria.minJumpZ) return null
        val logVolumes = baseline.mapNotNull { it.reliableVolume?.let(::ln1p) }
        val volume = current.reliableVolume
        val volumeZ = if (volume != null && logVolumes.size >= MIN_VOLUME_BASELINE) {
            max(0.0, ln1p(volume) - median(logVolumes)) / robustScale(logVolumes, LOG_VOLUME_FLOOR)
        } else Double.NaN
        val relativeVolume = if (volume != null && logVolumes.size >= MIN_VOLUME_BASELINE) {
            volume / baseline.mapNotNull(EarlyWindow::reliableVolume).let(::median).coerceAtLeast(1.0)
        } else Double.NaN
        val quality = 0.70 + 0.30 * current.efficiency
        val score = (0.65 * jumpZ + 0.25 * volumeZ.takeIf(Double::isFinite).orZero()) * quality
        return EarlyMomentum(current.returnPercent, current.efficiency, current.volume, jumpZ,
            volumeZ, relativeVolume, score, latest)
    }

    private fun window(bars: List<MinuteBar>): EarlyWindow? {
        if (bars.size != WINDOW_BARS || bars.first().open <= 0.0) return null
        val prices = listOf(bars.first().open) + bars.map(MinuteBar::close)
        val total = percent(prices.first(), prices.last())
        val path = prices.zipWithNext().sumOf { (first, second) -> abs(percent(first, second)) }
        val volume = bars.sumOf(MinuteBar::volume)
        val reliableVolume = volume.takeIf { bars.all { bar -> bar.volumeStatus.isReliable && bar.volume > 0.0 } }
        return EarlyWindow(local(bars.last()).toLocalDate(), total,
            if (path > 0.0) abs(total) / path else 0.0, volume, reliableVolume)
    }

    private fun isContinuous(bars: List<MinuteBar>): Boolean = bars.size == WINDOW_BARS &&
        bars.zipWithNext().all { (first, second) ->
            second.minuteEpochSeconds - first.minuteEpochSeconds == 60L &&
                local(first).toLocalDate() == local(second).toLocalDate()
        }

    private fun isDirectionallyConsistent(bars: List<MinuteBar>, totalReturn: Double): Boolean {
        val direction = if (totalReturn >= 0.0) 1 else -1
        val changes = (listOf(bars.first().open) + bars.map(MinuteBar::close)).zipWithNext { first, second ->
            percent(first, second) * direction
        }
        return changes.count { it > 0.0 } >= 2 && changes.last() > 0.0
    }

    private fun robustScale(values: List<Double>, floor: Double): Double {
        val center = median(values)
        val mad = median(values.map { abs(it - center) })
        return max(1.4826 * mad, max(abs(center) * 0.20, floor))
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))
    private fun percent(open: Double, close: Double) = (close / open - 1.0) * 100.0
    private fun Double?.orZero() = this ?: 0.0

    private data class EarlyWindow(
        val date: java.time.LocalDate,
        val returnPercent: Double,
        val efficiency: Double,
        val volume: Double,
        val reliableVolume: Double?
    )

    private companion object {
        const val WINDOW_BARS = 3
        const val MIN_MOVE_PERCENT = 0.35
        const val MIN_EFFICIENCY = 0.65
        const val TIME_RADIUS_MINUTES = 15L
        const val MIN_SESSIONS = 3
        const val MAX_SESSIONS = 20
        const val MIN_BASELINE = 15
        const val MIN_VOLUME_BASELINE = 10
        const val RETURN_FLOOR = 0.015
        const val LOG_VOLUME_FLOOR = 0.15
    }
}

internal data class EarlyMomentum(
    val returnPercent: Double,
    val efficiency: Double,
    val volume: Double,
    val jumpZ: Double,
    val volumeZ: Double,
    val relativeVolume: Double,
    val score: Double,
    val latestBar: MinuteBar
)
