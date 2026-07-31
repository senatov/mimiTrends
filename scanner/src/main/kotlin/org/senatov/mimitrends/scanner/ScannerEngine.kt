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
        val sorted = cleanBars(bars)
        val latest = sorted.lastOrNull() ?: return null
        val features = features(sorted)
        if (features.size < MIN_FEATURES) return null
        if (features.map { local(it.bar).toLocalDate() }.distinct().size < MIN_SESSIONS) {
            log.debug(LogTag.DB, "insufficient baseline sessions symbol={}", symbol)
            return null
        }
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
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = when (signal.ageMinutes) {
                0 -> "latest"
                1 -> "1m ago"
                else -> "${signal.ageMinutes}m ago"
            }
        )
    }

    fun evaluateFallback(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        log.debug(LogTag.DB, "evaluateFallback(symbol={}, bars={})", symbol, bars.size)
        val relaxed = criteria.copy(
            minJumpZ = criteria.minJumpZ * 0.80,
            minRangeZ = criteria.minRangeZ * 0.80,
            minVolumeZ = criteria.minVolumeZ * 0.75,
            minRelativeVolume = criteria.minRelativeVolume * 0.85,
            minBodyRatio = criteria.minBodyRatio * 0.90
        )
        evaluate(symbol, bars, relaxed)?.let { impulse ->
            return impulse.copy(signalSource = "${impulse.signalSource} · relaxed", anomalyScore = impulse.anomalyScore * 0.85)
        }
        return evaluateTrend(symbol, bars, criteria)
    }

    private fun evaluateTrend(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        val sorted = cleanBars(bars)
        val latest = sorted.lastOrNull() ?: return null
        val allDates = sorted.map { local(it).toLocalDate() }.distinct()
        if (allDates.size < MIN_SESSIONS) return null
        val session = sameSession(sorted, latest)
        val window = session.takeLast(criteria.trendWindowMinutes)
        if (window.size < MIN_TREND_BARS) return null
        val first = window.first().close
        val last = window.last().close
        val totalReturn = percent(first, last)
        if (totalReturn < criteria.minTrendReturnPercent) return null
        val path = window.zipWithNext().sumOf { (a, b) -> abs(percent(a.close, b.close)) }
        val efficiency = if (path > 0.0) totalReturn / path else 0.0
        if (efficiency < criteria.minTrendEfficiency) return null
        val regression = regression(window.map(MinuteBar::close))
        if (regression.slope <= 0.0 || regression.rSquared < MIN_TREND_R_SQUARED) return null
        val recentBars = window.takeLast(15)
        val recentReturn = percent(recentBars.first().close, recentBars.last().close)
        if (recentReturn < -MAX_RECENT_PULLBACK_PERCENT) return null
        val turnover = session.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        val score = totalReturn * (0.5 + regression.rSquared * 0.5) * (0.5 + efficiency.coerceAtMost(1.0) * 0.5)
        log.debug(LogTag.DB,
            "trend accepted symbol={} bars={} return={} efficiency={} rSquared={} recentReturn={}",
            symbol, window.size, totalReturn, efficiency, regression.rSquared, recentReturn)
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = score,
            priceAnomaly = Double.NaN,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = Double.NaN,
            windowChangePercent = totalReturn,
            windowVolume = window.sumOf(MinuteBar::volume),
            sessionVolume = session.sumOf(MinuteBar::volume),
            sessionTurnover = turnover,
            signalAgeMinutes = 0,
            signalSource = "Trend ↑",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000,
            signalWindowLabel = "${window.size}m"
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
        val meaningfulMove = abs(candidate.returnPercent) >= criteria.minAbsoluteMovePercent
        if (!exceptionalPrice || !confirmed || !directionalClose || !meaningfulMove) return null

        val freshness = exp(-age / FRESHNESS_HALF_LIFE)
        val quality = 0.60 + 0.40 * candidate.bodyRatio.coerceIn(0.0, 1.0)
        val raw = 0.65 * max(jumpZ, rangeZ * 0.8) + 0.25 * volumeZ + 0.10 * continuation
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
        val comparable = all.filter { feature ->
            feature.bar.minuteEpochSeconds < cutoff && local(feature.bar).toLocalDate() != candidateDate &&
                abs(java.time.Duration.between(local(feature.bar).toLocalTime(), candidateTime).toMinutes()) <= 15
        }.takeLast(sessions.coerceIn(3, 20) * 31)
        return if (comparable.size >= MIN_BASELINE) comparable
        else all.filter { it.bar.minuteEpochSeconds < cutoff }.takeLast(max(120, sessions * 100))
    }

    private fun cleanBars(bars: List<MinuteBar>): List<MinuteBar> = bars.asSequence()
        .filter { it.minuteEpochSeconds % 60L == 0L && it.volume > 0.0 }
        .sortedBy(MinuteBar::minuteEpochSeconds)
        .toList()

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

    private fun regression(values: List<Double>): Regression {
        val count = values.size.toDouble()
        val meanX = values.indices.sumOf { it.toDouble() } / count
        val meanY = values.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        values.forEachIndexed { index, value ->
            val dx = index - meanX
            val dy = value - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        val slope = if (varianceX > 0.0) covariance / varianceX else 0.0
        val rSquared = if (varianceX > 0.0 && varianceY > 0.0) covariance * covariance / (varianceX * varianceY) else 0.0
        return Regression(slope, rSquared.coerceIn(0.0, 1.0))
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

    private data class Regression(val slope: Double, val rSquared: Double)

    private companion object {
        const val MIN_FEATURES = 20
        const val MIN_SESSIONS = 2
        const val MIN_BASELINE = 15
        const val RETURN_FLOOR = 0.01
        const val RANGE_FLOOR = 0.01
        const val LOG_VOLUME_FLOOR = 0.15
        const val FRESHNESS_HALF_LIFE = 1.8
        const val MIN_TREND_BARS = 60
        const val MIN_TREND_R_SQUARED = 0.18
        const val MAX_RECENT_PULLBACK_PERCENT = 0.35
    }
}
