package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult
import kotlin.math.abs

internal object CandidateQualityGate {
    fun qualifies(result: ScanResult, adaptive: Boolean): Boolean {
        if (!isCurrent(result) || result.signalSource.contains("watch")) return false
        if (adaptive && !adaptiveQuality(result)) return false
        return structuralQuality(result, watch = false)
    }

    fun qualifiesWatch(result: ScanResult): Boolean {
        if (!isCurrent(result) || !watchRankingQuality(result)) return false
        return structuralQuality(result, watch = true)
    }

    private fun structuralQuality(result: ScanResult, watch: Boolean): Boolean {
        return when {
            result.signalSource.startsWith("Steady rise") || result.signalSource.startsWith("Recovery") ||
                result.signalSource.startsWith("Trend") -> trendQuality(result, watch)
            result.signalSource.startsWith("V-Reversal") -> reversalQuality(result, watch)
            else -> impulseQuality(result, watch)
        }
    }

    fun attentionScore(result: ScanResult): Double {
        val historical = result.rankingPercentile.takeIf(Double::isFinite)?.div(10.0)
            ?: (result.anomalyScore / 8.0).coerceIn(0.0, 1.0)
        val marketMove = minimumMove(result)
        val movement = (abs(result.windowChangePercent) / (marketMove * 2.5)).coerceIn(0.0, 1.0)
        val freshness = (1.0 - result.signalAgeMinutes / 3.0).coerceIn(0.0, 1.0)
        val volume = when {
            result.volumeAnomaly.isFinite() -> (result.volumeAnomaly / 4.0).coerceIn(0.0, 1.0)
            result.relativeVolume.isFinite() -> (result.relativeVolume / 3.0).coerceIn(0.0, 1.0)
            else -> 0.35
        }
        val persistence = result.candleBodyRatio.coerceIn(0.0, 1.0)
        val penalty = (if (result.signalSource.contains("relaxed")) 0.10 else 0.0) +
            (if (!result.volumeAnomaly.isFinite() && !result.relativeVolume.isFinite()) 0.05 else 0.0) +
            (if (result.signalSource.contains("watch")) 0.15 else 0.0) +
            (if ('↓' in result.signalSource) 0.20 else 0.0)
        return 10.0 * (0.35 * historical + 0.25 * movement + 0.15 * freshness +
            0.15 * volume + 0.10 * persistence - penalty)
    }

    fun priorityTier(result: ScanResult): Int = when {
        result.signalSource.contains("downside watch") -> DOWNSIDE_WATCH_TIER
        result.signalSource.contains("watch") -> WATCH_TIER
        '↓' in result.signalSource -> DOWNSIDE_WATCH_TIER
        else -> LONG_TIER
    }

    private fun impulseQuality(result: ScanResult, watch: Boolean): Boolean {
        val move = abs(result.windowChangePercent)
        val moveFloor = minimumMove(result) * if (watch) WATCH_THRESHOLD_FACTOR else 1.0
        val jumpFloor = if (watch) WATCH_MIN_JUMP_Z else MIN_JUMP_Z
        val rangeFloor = if (watch) WATCH_MIN_RANGE_Z else MIN_RANGE_Z
        val priceQuality = result.priceAnomaly >= jumpFloor || result.rangeAnomaly >= rangeFloor
        val volumeAvailable = result.volumeAnomaly.isFinite() || result.relativeVolume.isFinite()
        val thresholdFactor = if (watch) WATCH_THRESHOLD_FACTOR else 1.0
        val confirmation = if (volumeAvailable) {
            result.volumeAnomaly >= MIN_VOLUME_Z * thresholdFactor ||
                result.relativeVolume >= MIN_RELATIVE_VOLUME * thresholdFactor
        } else {
            result.priceAnomaly >= NO_VOLUME_JUMP_Z * thresholdFactor &&
                move >= NO_VOLUME_MOVE_PERCENT * thresholdFactor
        }
        return move >= moveFloor && priceQuality && confirmation &&
            result.candleBodyRatio >= MIN_BODY_RATIO * thresholdFactor
    }

    private fun trendQuality(result: ScanResult, watch: Boolean): Boolean =
        result.signalWindowLabel.filter(Char::isDigit).toIntOrNull()?.let {
            it >= if (watch) WATCH_MIN_TREND_MINUTES else MIN_TREND_MINUTES
        } == true && abs(result.windowChangePercent) >=
            (if (watch) WATCH_MIN_TREND_RETURN_PERCENT else MIN_TREND_RETURN_PERCENT) &&
            result.candleBodyRatio >= (if (watch) WATCH_MIN_TREND_EFFICIENCY else MIN_TREND_EFFICIENCY)

    private fun reversalQuality(result: ScanResult, watch: Boolean): Boolean =
        result.priceAnomaly >= (if (watch) WATCH_MIN_JUMP_Z else MIN_JUMP_Z) &&
            abs(result.windowChangePercent) >= minimumMove(result) *
            (if (watch) WATCH_THRESHOLD_FACTOR else 1.0) &&
            result.candleBodyRatio >=
            (if (watch) WATCH_MIN_REVERSAL_RECOVERY_RATIO else MIN_REVERSAL_RECOVERY_RATIO)

    private fun watchRankingQuality(result: ScanResult): Boolean =
        result.rankingPercentile.takeIf(Double::isFinite)?.let { it >= MIN_WATCH_PERCENTILE }
            ?: (result.anomalyScore >= if (isTrend(result)) MIN_WATCH_TREND_SCORE else MIN_WATCH_SCORE)

    private fun isCurrent(result: ScanResult): Boolean =
        result.signalAgeMinutes <= MAX_SIGNAL_AGE_MINUTES && !result.signalSource.contains("cooling")

    private fun adaptiveQuality(result: ScanResult): Boolean =
        result.rankingPercentile.takeIf(Double::isFinite)?.let { it >= MIN_ADAPTIVE_PERCENTILE }
            ?: (result.anomalyScore >= if (isTrend(result)) MIN_UNCALIBRATED_TREND_SCORE
                else MIN_UNCALIBRATED_ADAPTIVE_SCORE)

    private fun isTrend(result: ScanResult): Boolean =
        result.signalSource.startsWith("Steady rise") || result.signalSource.startsWith("Recovery") ||
            result.signalSource.startsWith("Trend")

    private fun minimumMove(result: ScanResult): Double =
        if (result.symbol.contains('.')) MIN_EUROPE_MOVE_PERCENT else MIN_US_MOVE_PERCENT

    private const val MAX_SIGNAL_AGE_MINUTES = 2
    private const val MIN_EUROPE_MOVE_PERCENT = 0.45
    private const val MIN_US_MOVE_PERCENT = 0.60
    private const val MIN_JUMP_Z = 3.8
    private const val MIN_RANGE_Z = 4.0
    private const val MIN_VOLUME_Z = 2.5
    private const val MIN_RELATIVE_VOLUME = 2.0
    private const val NO_VOLUME_JUMP_Z = 4.5
    private const val NO_VOLUME_MOVE_PERCENT = 0.75
    private const val MIN_BODY_RATIO = 0.65
    private const val MIN_TREND_MINUTES = 15
    private const val MIN_TREND_RETURN_PERCENT = 0.35
    private const val MIN_TREND_EFFICIENCY = 0.20
    private const val MIN_REVERSAL_RECOVERY_RATIO = 0.45
    private const val MIN_ADAPTIVE_PERCENTILE = 8.0
    private const val MIN_UNCALIBRATED_ADAPTIVE_SCORE = 5.0
    private const val MIN_UNCALIBRATED_TREND_SCORE = 3.25
    private const val MIN_WATCH_PERCENTILE = 5.5
    private const val MIN_WATCH_SCORE = 2.0
    private const val MIN_WATCH_TREND_SCORE = 2.5
    private const val WATCH_THRESHOLD_FACTOR = 0.75
    private const val WATCH_MIN_JUMP_Z = 3.2
    private const val WATCH_MIN_RANGE_Z = 3.5
    private const val WATCH_MIN_TREND_MINUTES = 10
    private const val WATCH_MIN_TREND_RETURN_PERCENT = 0.25
    private const val WATCH_MIN_TREND_EFFICIENCY = 0.15
    private const val WATCH_MIN_REVERSAL_RECOVERY_RATIO = 0.35
    private const val LONG_TIER = 0
    private const val WATCH_TIER = 1
    private const val DOWNSIDE_WATCH_TIER = 2
}
