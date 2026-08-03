package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.isValidMinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max

/** Detects fresh directional impulses, early momentum, and V-shaped reversals. */
class ScannerEngine(private val zoneOverride: ZoneId? = null) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val earlyMomentumDetector = EarlyMomentumDetector(zoneOverride)
    private val vReversalDetector = VReversalDetector(zoneOverride)
    private val steadyRiseDetector = SteadyRiseDetector(zoneOverride)

    internal fun freshnessWeight(ageMinutes: Double): Double =
        exp(-ln(2.0) * ageMinutes.coerceAtLeast(0.0) / FRESHNESS_HALF_LIFE_MINUTES)

    fun evaluate(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        log.debug(LogTag.DB, "evaluate(symbol={}, bars={}, maxAge={}m)", symbol, bars.size, criteria.maxSignalAgeMinutes)
        val sorted = cleanBars(bars)
        val latest = sorted.lastOrNull() ?: return null
        val features = features(sorted)
        if (features.size < MIN_FEATURES) return null
        if (features.map { local(it.bar).toLocalDate() }.distinct().size < MIN_IMPULSE_SESSIONS) {
            log.debug(LogTag.DB, "insufficient baseline sessions symbol={}", symbol)
            return null
        }
        val candidates = features.takeLast(criteria.maxSignalAgeMinutes + 1).mapNotNull { candidate ->
            score(candidate, features, latest, criteria)?.takeUnless {
                RangeRegimeFilter.blocks(sorted, candidate.bar, candidate.returnPercent.direction())
            }
        }
        val signal = candidates.maxByOrNull(Signal::score)
        val momentum = earlyMomentumDetector.detect(sorted, criteria)?.takeUnless {
            RangeRegimeFilter.blocks(sorted, it.latestBar, it.returnPercent.direction())
        }
        val reversal = vReversalDetector.detect(sorted, criteria)?.takeUnless {
            RangeRegimeFilter.blocks(sorted, it.latestBar, it.direction)
        }
        if (signal == null && momentum == null && reversal == null) return null
        val sessionBars = sameSession(sorted, latest)
        val turnover = sessionBars.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        val impulseScore = signal?.score ?: Double.NEGATIVE_INFINITY
        if (reversal != null && reversal.score >= max(impulseScore, momentum?.score ?: Double.NEGATIVE_INFINITY)) {
            return reversalResult(symbol, latest, sessionBars, turnover, reversal)
        }
        if (momentum != null && momentum.score > impulseScore) {
            return momentumResult(symbol, latest, sessionBars, turnover, momentum)
        }
        val selected = requireNotNull(signal)
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = selected.score,
            priceAnomaly = selected.jumpZ,
            volumeAnomaly = selected.volumeZ,
            rangeAnomaly = selected.rangeZ,
            relativeVolume = selected.relativeVolume,
            candleBodyRatio = selected.feature.bodyRatio,
            windowChangePercent = displayWindowChange(sorted, latest),
            windowVolume = selected.feature.bar.volume,
            sessionVolume = sessionBars.sumOf(MinuteBar::volume),
            sessionTurnover = turnover,
            signalAgeMinutes = selected.ageMinutes,
            signalSource = if (selected.feature.returnPercent >= 0) "Impulse ↑" else "Impulse ↓",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = when (selected.ageMinutes) {
                0 -> "latest"
                1 -> "1m ago"
                else -> "${selected.ageMinutes}m ago"
            },
            signalPrice = selected.feature.bar.close,
            signalEpochMillis = selected.feature.bar.minuteEpochSeconds * 1_000
        )
    }

    private fun momentumResult(
        symbol: String,
        latest: MinuteBar,
        sessionBars: List<MinuteBar>,
        turnover: Double,
        momentum: EarlyMomentum
    ) = ScanResult(
        symbol = symbol,
        price = latest.close,
        anomalyScore = momentum.score,
        priceAnomaly = momentum.jumpZ,
        volumeAnomaly = momentum.volumeZ,
        rangeAnomaly = Double.NaN,
        relativeVolume = momentum.relativeVolume,
        candleBodyRatio = momentum.efficiency,
        windowChangePercent = momentum.returnPercent,
        windowVolume = momentum.volume,
        sessionVolume = sessionBars.sumOf(MinuteBar::volume),
        sessionTurnover = turnover,
        signalAgeMinutes = 0,
        signalSource = if (momentum.returnPercent >= 0.0) "Momentum 3m ↑" else "Momentum 3m ↓",
        updatedAtMillis = latest.minuteEpochSeconds * 1_000,
        signalWindowLabel = "3m acceleration",
        signalPrice = latest.close,
        signalEpochMillis = latest.minuteEpochSeconds * 1_000
    )

    private fun reversalResult(
        symbol: String,
        latest: MinuteBar,
        sessionBars: List<MinuteBar>,
        turnover: Double,
        reversal: VReversal
    ) = ScanResult(
        symbol = symbol,
        price = latest.close,
        anomalyScore = reversal.score,
        priceAnomaly = reversal.shockZ,
        volumeAnomaly = Double.NaN,
        rangeAnomaly = reversal.shockZ,
        relativeVolume = Double.NaN,
        candleBodyRatio = reversal.recoveryRatio.coerceIn(0.0, 1.0),
        windowChangePercent = reversal.recoveryPercent,
        windowVolume = reversal.volume,
        sessionVolume = sessionBars.sumOf(MinuteBar::volume),
        sessionTurnover = turnover,
        signalAgeMinutes = 0,
        signalSource = if (reversal.direction > 0) "V-Reversal ↑" else "V-Reversal ↓",
        updatedAtMillis = latest.minuteEpochSeconds * 1_000,
        signalWindowLabel = "${reversal.extremeAgeMinutes}m recovery",
        signalPrice = latest.close,
        signalEpochMillis = latest.minuteEpochSeconds * 1_000
    )

    fun evaluateFallback(
        symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria, relaxation: Double = 0.80
    ): ScanResult? {
        val factor = relaxation.coerceIn(0.55, 0.90)
        log.debug(LogTag.DB, "evaluateFallback(symbol={}, bars={}, factor={})", symbol, bars.size, factor)
        val relaxed = criteria.copy(
            minJumpZ = criteria.minJumpZ * factor,
            minRangeZ = criteria.minRangeZ * factor,
            minVolumeZ = criteria.minVolumeZ * factor,
            minRelativeVolume = criteria.minRelativeVolume * (0.75 + factor * 0.25),
            minBodyRatio = criteria.minBodyRatio * (0.75 + factor * 0.25),
            minAbsoluteMovePercent = criteria.minAbsoluteMovePercent * (0.70 + factor * 0.30),
            minTrendReturnPercent = criteria.minTrendReturnPercent * factor,
            minTrendEfficiency = criteria.minTrendEfficiency * (0.75 + factor * 0.25)
        )
        evaluate(symbol, bars, relaxed)?.let { impulse ->
            return impulse.copy(signalSource = "${impulse.signalSource} · relaxed", anomalyScore = impulse.anomalyScore * factor)
        }
        return steadyRiseDetector.detect(symbol, bars, relaxed)
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
        val logVolumes = baseline.mapNotNull { feature ->
            feature.bar.volume.takeIf { feature.bar.volumeStatus.isReliable && it > 0.0 }?.let(::ln1p)
        }
        val jumpZ = abs(candidate.returnPercent - median(returns)) / robustScale(returns, RETURN_FLOOR)
        val rangeZ = max(0.0, candidate.rangePercent - median(ranges)) / robustScale(ranges, RANGE_FLOOR)
        val candidateVolume = candidate.bar.volume.takeIf { candidate.bar.volumeStatus.isReliable && it > 0.0 }
        val volumeZ = if (candidateVolume != null && logVolumes.size >= MIN_VOLUME_BASELINE) {
            max(0.0, ln1p(candidateVolume) - median(logVolumes)) / robustScale(logVolumes, LOG_VOLUME_FLOOR)
        } else Double.NaN
        val normalVolumes = baseline.map(Feature::bar)
            .filter { it.volumeStatus.isReliable && it.volume > 0.0 }.map(MinuteBar::volume)
        val relativeVolume = if (candidateVolume != null && normalVolumes.size >= MIN_VOLUME_BASELINE) {
            candidateVolume / median(normalVolumes).coerceAtLeast(1.0)
        } else Double.NaN
        val previous = all.getOrNull(candidate.index - 1)
        val continuation = if (previous != null && isImmediateContinuation(previous, candidate, criteria)) 1.0 else 0.0
        val directionalClose = if (candidate.returnPercent >= 0) candidate.closeLocation >= 0.70 else candidate.closeLocation <= 0.30
        val confirmed = (volumeZ.isFinite() && volumeZ >= criteria.minVolumeZ) ||
            (relativeVolume.isFinite() && relativeVolume >= criteria.minRelativeVolume) ||
            candidate.bodyRatio >= criteria.minBodyRatio || continuation > 0.0
        val exceptionalPrice = jumpZ >= criteria.minJumpZ || rangeZ >= criteria.minRangeZ
        val meaningfulMove = abs(candidate.returnPercent) >= criteria.minAbsoluteMovePercent
        if (!exceptionalPrice || !confirmed || !directionalClose || !meaningfulMove) return null

        val freshness = freshnessWeight(age.toDouble())
        val quality = 0.60 + 0.40 * candidate.bodyRatio.coerceIn(0.0, 1.0)
        val raw = 0.65 * max(jumpZ, rangeZ * 0.8) +
            0.25 * volumeZ.takeIf(Double::isFinite).orZero() + 0.10 * continuation
        val signal = Signal(candidate, age, jumpZ, rangeZ, volumeZ, relativeVolume, raw * quality * freshness)
        log.debug(LogTag.DB,
            "impulse accepted symbol={} age={} return={} jumpZ={} rangeZ={} volumeZ={} rvol={} body={}",
            candidate.bar.symbol, age, candidate.returnPercent, jumpZ, rangeZ, volumeZ, relativeVolume, candidate.bodyRatio)
        return signal
    }

    private fun baseline(all: List<Feature>, candidate: Feature, sessions: Int): List<Feature> {
        val cutoff = candidate.bar.minuteEpochSeconds - 15 * 60L
        val candidateTime = local(candidate.bar).toLocalTime()
        val candidateDate = local(candidate.bar).toLocalDate()
        val historical = all.filter { feature ->
            feature.bar.minuteEpochSeconds < cutoff && local(feature.bar).toLocalDate() != candidateDate &&
                abs(java.time.Duration.between(local(feature.bar).toLocalTime(), candidateTime).toMinutes()) <= BASELINE_TIME_RADIUS_MINUTES
        }
        val selectedDates = historical.asReversed().map { local(it.bar).toLocalDate() }.distinct()
            .take(sessions.coerceIn(MIN_BASELINE_SESSIONS, MAX_BASELINE_SESSIONS)).toSet()
        if (selectedDates.size < MIN_BASELINE_SESSIONS) return emptyList()
        return historical.filter { local(it.bar).toLocalDate() in selectedDates }
    }

    private fun isImmediateContinuation(previous: Feature, candidate: Feature, criteria: ScannerCriteria): Boolean {
        val seconds = candidate.bar.minuteEpochSeconds - previous.bar.minuteEpochSeconds
        if (seconds != 60L || local(previous.bar).toLocalDate() != local(candidate.bar).toLocalDate()) return false
        val sameDirection = previous.returnPercent * candidate.returnPercent > 0.0
        val materialPreviousMove = abs(previous.returnPercent) >= criteria.minAbsoluteMovePercent * 0.25
        return sameDirection && materialPreviousMove
    }

    private fun cleanBars(bars: List<MinuteBar>): List<MinuteBar> = bars.asSequence()
        .filter(MinuteBar::isValidMinuteBar)
        .sortedBy(MinuteBar::minuteEpochSeconds)
        .toList()

    private fun features(bars: List<MinuteBar>): List<Feature> = bars.zipWithNext().mapIndexedNotNull { index, (previous, bar) ->
        val seconds = bar.minuteEpochSeconds - previous.minuteEpochSeconds
        if (seconds !in 1..180 || previous.close <= 0.0) return@mapIndexedNotNull null
        val referencePrice = if (seconds == 60L) previous.close else bar.open
        if (referencePrice <= 0.0) return@mapIndexedNotNull null
        val range = (bar.high - bar.low).coerceAtLeast(0.0)
        Feature(
            index = index,
            bar = bar,
            returnPercent = percent(referencePrice, bar.close),
            rangePercent = range / referencePrice * 100.0,
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

    private fun displayWindowChange(bars: List<MinuteBar>, latest: MinuteBar): Double {
        val cutoff = latest.minuteEpochSeconds - DISPLAY_CHANGE_MINUTES * 60L
        val first = bars.firstOrNull { it.minuteEpochSeconds >= cutoff } ?: latest
        return percent(first.close, latest.close)
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))
    private fun percent(open: Double, close: Double) = if (open > 0.0) (close / open - 1.0) * 100.0 else 0.0
    private fun Double?.orZero() = this ?: 0.0
    private fun Double.direction() = if (this >= 0.0) 1 else -1

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
        private const val FRESHNESS_HALF_LIFE_MINUTES = 1.8

        const val MIN_FEATURES = 20
        const val MIN_IMPULSE_SESSIONS = 4
        const val MIN_BASELINE = 15
        const val MIN_VOLUME_BASELINE = 10
        const val MIN_BASELINE_SESSIONS = 3
        const val MAX_BASELINE_SESSIONS = 20
        const val BASELINE_TIME_RADIUS_MINUTES = 15L
        const val RETURN_FLOOR = 0.01
        const val RANGE_FLOOR = 0.01
        const val LOG_VOLUME_FLOOR = 0.15
        const val DISPLAY_CHANGE_MINUTES = 10
    }
}
