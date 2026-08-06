package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult
import kotlin.math.abs

internal object CandidateQualityGate {
    fun qualifies(result: ScanResult, adaptive: Boolean): Boolean {
        if (result.signalAgeMinutes > MAX_SIGNAL_AGE_MINUTES || result.signalSource.contains("cooling")) return false
        if (adaptive && !adaptiveQuality(result)) return false
        return when {
            result.signalSource.startsWith("Steady rise") || result.signalSource.startsWith("Recovery") ||
                result.signalSource.startsWith("Trend") -> trendQuality(result)
            result.signalSource.startsWith("V-Reversal") -> reversalQuality(result)
            else -> impulseQuality(result)
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
            (if (priorityTier(result) > 0) 0.20 else 0.0)
        return 10.0 * (0.35 * historical + 0.25 * movement + 0.15 * freshness +
            0.15 * volume + 0.10 * persistence - penalty)
    }

    fun priorityTier(result: ScanResult): Int = if ('↓' in result.signalSource) DOWNSIDE_WATCH_TIER else LONG_TIER

    private fun impulseQuality(result: ScanResult): Boolean {
        val move = abs(result.windowChangePercent)
        val priceQuality = result.priceAnomaly >= MIN_JUMP_Z || result.rangeAnomaly >= MIN_RANGE_Z
        val volumeAvailable = result.volumeAnomaly.isFinite() || result.relativeVolume.isFinite()
        val confirmation = if (volumeAvailable) {
            result.volumeAnomaly >= MIN_VOLUME_Z || result.relativeVolume >= MIN_RELATIVE_VOLUME
        } else {
            result.priceAnomaly >= NO_VOLUME_JUMP_Z && move >= NO_VOLUME_MOVE_PERCENT
        }
        return move >= minimumMove(result) && priceQuality && confirmation &&
            result.candleBodyRatio >= MIN_BODY_RATIO
    }

    private fun trendQuality(result: ScanResult): Boolean =
        result.signalWindowLabel.filter(Char::isDigit).toIntOrNull()?.let { it >= MIN_TREND_MINUTES } == true &&
            abs(result.windowChangePercent) >= MIN_TREND_RETURN_PERCENT &&
            result.candleBodyRatio >= MIN_TREND_EFFICIENCY

    private fun reversalQuality(result: ScanResult): Boolean =
        result.priceAnomaly >= MIN_JUMP_Z && abs(result.windowChangePercent) >= minimumMove(result) &&
            result.candleBodyRatio >= MIN_REVERSAL_RECOVERY_RATIO

    private fun adaptiveQuality(result: ScanResult): Boolean =
        result.rankingPercentile.takeIf(Double::isFinite)?.let { it >= MIN_ADAPTIVE_PERCENTILE }
            ?: (result.anomalyScore >= MIN_UNCALIBRATED_ADAPTIVE_SCORE)

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
    private const val MIN_TREND_MINUTES = 30
    private const val MIN_TREND_RETURN_PERCENT = 0.80
    private const val MIN_TREND_EFFICIENCY = 0.20
    private const val MIN_REVERSAL_RECOVERY_RATIO = 0.45
    private const val MIN_ADAPTIVE_PERCENTILE = 8.0
    private const val MIN_UNCALIBRATED_ADAPTIVE_SCORE = 5.0
    private const val LONG_TIER = 0
    private const val DOWNSIDE_WATCH_TIER = 1
}
