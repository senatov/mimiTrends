package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max

/** Detects only directional impulses in the latest completed minute bars. */
class ScannerEngine(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun evaluate(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        log.debug(LogTag.DB, "evaluate(symbol={}, bars={}, maxAge={}m)", symbol, bars.size, criteria.maxSignalAgeMinutes)
        val sorted = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = sorted.lastOrNull() ?: return null
        val features = features(sorted)
        if (features.size < MIN_FEATURES) return null
        val candidates = features.takeLast(criteria.maxSignalAgeMinutes + 1).mapNotNull { candidate ->
            score(candidate, features, latest, criteria)
        }
        val signal = candidates.maxByOrNull(Signal::score) ?: return null
        val sessionBars = sameSession(sorted, latest)
        val turnover = sessionBars.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = signal.score,
            priceAnomaly = signal.jumpZ,
            volumeAnomaly = signal.volumeZ,
            rangeAnomaly = signal.rangeZ,
            relativeVolume = signal.relativeVolume,
            candleBodyRatio = signal.feature.bodyRatio,
            windowChangePercent = signal.feature.returnPercent,
            windowVolume = signal.feature.bar.volume,
            sessionVolume = sessionBars.sumOf(MinuteBar::volume),
            sessionTurnover = turnover,
            signalAgeMinutes = signal.ageMinutes,
            signalSource = if (signal.feature.returnPercent >= 0) "Impulse ↑" else "Impulse ↓",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun score(
        candidate: Feature,
        all: List<Feature>,
        latest: MinuteBar,
        criteria: ScannerCriteria
    ): Signal? {
        val age = ((latest.minuteEpochSeconds - candidate.bar.minuteEpochSeconds) / 60L).toInt().coerceAtLeast(0)
        if (age > criteria.maxSignalAgeMinutes) return null
        val baseline = baseline(all, candidate, criteria.baselineSessions)
        if (baseline.size < MIN_BASELINE) return null

        val returns = baseline.map(Feature::returnPercent)
        val ranges = baseline.map(Feature::rangePercent)
        val logVolumes = baseline.map { ln1p(it.bar.volume.coerceAtLeast(0.0)) }
        val jumpZ = abs(candidate.returnPercent - median(returns)) / robustScale(returns, RETURN_FLOOR)
        val rangeZ = max(0.0, candidate.rangePercent - median(ranges)) / robustScale(ranges, RANGE_FLOOR)
        val volumeZ = max(0.0, ln1p(candidate.bar.volume.coerceAtLeast(0.0)) - median(logVolumes)) /
            robustScale(logVolumes, LOG_VOLUME_FLOOR)
        val normalVolume = median(baseline.map { it.bar.volume }.filter { it > 0.0 }).coerceAtLeast(1.0)
        val relativeVolume = candidate.bar.volume / normalVolume
        val previous = all.getOrNull(candidate.index - 1)
        val continuation = if (previous != null && previous.returnPercent * candidate.returnPercent > 0.0) 1.0 else 0.0
        val directionalClose = if (candidate.returnPercent >= 0) candidate.closeLocation >= 0.70 else candidate.closeLocation <= 0.30
        val confirmed = volumeZ >= criteria.minVolumeZ || relativeVolume >= criteria.minRelativeVolume ||
            candidate.bodyRatio >= criteria.minBodyRatio || continuation > 0.0
        val exceptionalPrice = jumpZ >= criteria.minJumpZ || rangeZ >= criteria.minRangeZ
        if (!exceptionalPrice || !confirmed || !directionalClose) return null

        val freshness = exp(-age / FRESHNESS_HALF_LIFE)
        val quality = 0.60 + 0.40 * candidate.bodyRatio.coerceIn(0.0, 1.0)
        val raw = 0.65 * max(jumpZ, rangeZ * 0.8) + 0.25 * volumeZ + 0.10 * continuation
        return Signal(candidate, age, jumpZ, rangeZ, volumeZ, relativeVolume, raw * quality * freshness)
    }

    private fun baseline(all: List<Feature>, candidate: Feature, sessions: Int): List<Feature> {
        val cutoff = candidate.bar.minuteEpochSeconds - 15 * 60L
        val candidateTime = local(candidate.bar).toLocalTime()
        val candidateDate = local(candidate.bar).toLocalDate()
        val comparable = all.filter { feature ->
            feature.bar.minuteEpochSeconds < cutoff && local(feature.bar).toLocalDate() != candidateDate &&
                abs(java.time.Duration.between(local(feature.bar).toLocalTime(), candidateTime).toMinutes()) <= 15
        }.takeLast(sessions.coerceIn(3, 20) * 31)
        return if (comparable.size >= MIN_BASELINE) comparable
        else all.filter { it.bar.minuteEpochSeconds < cutoff }.takeLast(max(120, sessions * 100))
    }

    private fun features(bars: List<MinuteBar>): List<Feature> = bars.zipWithNext().mapIndexedNotNull { index, (previous, bar) ->
        val seconds = bar.minuteEpochSeconds - previous.minuteEpochSeconds
        if (seconds !in 1..180 || previous.close <= 0.0) return@mapIndexedNotNull null
        val range = (bar.high - bar.low).coerceAtLeast(0.0)
        Feature(
            index = index,
            bar = bar,
            returnPercent = percent(previous.close, bar.close),
            rangePercent = range / previous.close * 100.0,
            bodyRatio = if (range > 0.0) abs(bar.close - bar.open) / range else 0.0,
            closeLocation = if (range > 0.0) (bar.close - bar.low) / range else 0.5
        )
    }.mapIndexed { index, feature -> feature.copy(index = index) }

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

    private fun sameSession(bars: List<MinuteBar>, latest: MinuteBar): List<MinuteBar> {
        val date = local(latest).toLocalDate()
        return bars.filter { local(it).toLocalDate() == date }
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds).atZone(zone)
    private fun percent(open: Double, close: Double) = if (open > 0.0) (close / open - 1.0) * 100.0 else 0.0

    private data class Feature(
        val index: Int,
        val bar: MinuteBar,
        val returnPercent: Double,
        val rangePercent: Double,
        val bodyRatio: Double,
        val closeLocation: Double
    )

    private data class Signal(
        val feature: Feature,
        val ageMinutes: Int,
        val jumpZ: Double,
        val rangeZ: Double,
        val volumeZ: Double,
        val relativeVolume: Double,
        val score: Double
    )

    private companion object {
        const val MIN_FEATURES = 20
        const val MIN_BASELINE = 15
        const val RETURN_FLOOR = 0.01
        const val RANGE_FLOOR = 0.01
        const val LOG_VOLUME_FLOOR = 0.15
        const val FRESHNESS_HALF_LIFE = 1.8
    }
}
