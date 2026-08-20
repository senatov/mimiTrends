package org.senatov.mimitrends

import org.senatov.mimitrends.db.DownsideSafetyCalibration
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ResearchFeatures
import java.time.Instant
import java.time.LocalTime

internal data class ShortTermSafetyAssessment(
    val score: Int,
    val confidence: Int,
    val label: String,
    val details: String
)

/** Conservative 60–90 minute downside-safety estimate; deliberately not a return forecast. */
internal object ShortTermSafetyModel {
    fun assess(
        symbol: String,
        bars: List<MinuteBar>,
        features: ResearchFeatures,
        entry: EntryQualityAssessment,
        longTermTrendScore: Int?,
        nowEpochSeconds: Long,
        calibration: DownsideSafetyCalibration? = null
    ): ShortTermSafetyAssessment {
        val latest = bars.maxByOrNull(MinuteBar::minuteEpochSeconds)
            ?: return unavailable()
        val zone = MarketTimeZone.forSymbol(symbol)
        val latestTime = Instant.ofEpochSecond(latest.minuteEpochSeconds).atZone(zone)
        val regularSession = isRegularSession(symbol, latestTime.toLocalTime())
        val recent = bars.asSequence().filter {
            it.minuteEpochSeconds <= latest.minuteEpochSeconds &&
                it.minuteEpochSeconds >= latest.minuteEpochSeconds - RECENT_WINDOW_MINUTES * 60L
        }.sortedBy(MinuteBar::minuteEpochSeconds).toList()
        val coverage = recent.size / RECENT_WINDOW_MINUTES.toDouble()
        val reliableVolumeShare = recent.count { it.volumeStatus.isReliable && it.volume > 0.0 } /
            recent.size.coerceAtLeast(1).toDouble()
        val freshness = (1.0 - (nowEpochSeconds - latest.minuteEpochSeconds).coerceAtLeast(0L) /
            MAX_FRESH_AGE_SECONDS.toDouble()).coerceIn(0.0, 1.0)
        val drawdown = recent.maxOfOrNull(MinuteBar::high)?.let { peak ->
            if (peak > 0.0) (latest.close / peak - 1.0) * 100.0 else Double.NaN
        } ?: Double.NaN
        val negativeMomentum = maxOf(
            negativeRisk(features.return3mPercent, 0.08, 0.55),
            negativeRisk(features.return5mPercent, 0.12, 0.80)
        )
        val negativeAcceleration = if (features.return1mPercent.isFinite() && features.return3mPercent.isFinite()) {
            negativeRisk(features.return1mPercent - features.return3mPercent / 3.0, 0.03, 0.30)
        } else 0.0
        val belowVwap = negativeRisk(features.vwapDistancePercent, 0.05, 0.75)
        val fallingEfficiency = features.trendEfficiency10m.takeIf(Double::isFinite)
            ?.let { (-it).coerceIn(0.0, 1.0) } ?: 0.0
        val pullbackRisk = negativeRisk(drawdown, 0.08, 0.70)
        val volatilityRisk = features.realizedVolatility30m.takeIf(Double::isFinite)
            ?.let { ((it - 0.18) / 0.55).coerceIn(0.0, 1.0) } ?: 0.45
        val entryContribution = (entry.score - 50.0) * 0.24
        val trendContribution = ((longTermTrendScore ?: 50) - 50.0) * 0.05
        val heuristic = 57.0 + entryContribution + trendContribution -
            24.0 * negativeMomentum - 15.0 * negativeAcceleration - 14.0 * belowVwap -
            12.0 * fallingEfficiency - 13.0 * pullbackRisk - 8.0 * volatilityRisk
        val calibrationWeight = (calibration?.confidence ?: 0) / 100.0 * MAX_CALIBRATION_WEIGHT
        val raw = heuristic * (1.0 - calibrationWeight) +
            (calibration?.probability?.times(100.0) ?: heuristic) * calibrationWeight
        val confidence = (100.0 * coverage.coerceIn(0.0, 1.0) * freshness *
            (0.45 + 0.55 * reliableVolumeShare) * (0.45 + 0.55 * entry.confidence / 100.0) *
            if (regularSession) 1.0 else OFF_SESSION_CONFIDENCE_FACTOR).toInt().coerceIn(0, 100)
        val reliability = 0.25 + 0.75 * confidence / 100.0
        var score = (50.0 + (raw - 50.0) * reliability).toInt().coerceIn(0, 100)
        val reversal = negativeMomentum >= 0.45 || negativeAcceleration >= 0.55 ||
            (belowVwap >= 0.35 && fallingEfficiency >= 0.35)
        if (!regularSession) score = score.coerceAtMost(49)
        if (reliableVolumeShare < MIN_RELIABLE_VOLUME_SHARE) score = score.coerceAtMost(54)
        if (entry.cooldownMinutes > 0) score = score.coerceAtMost(44)
        if (reversal) score = score.coerceAtMost(39)
        val label = when {
            !regularSession -> "Outside regular session"
            confidence < MIN_ACTIONABLE_CONFIDENCE -> "Limited data"
            reversal -> "Reversal risk"
            score >= 70 -> "Stable setup"
            score >= 56 -> "Caution"
            else -> "Downside risk"
        }
        val details = buildString {
            append("Estimated resistance to a material drawdown over 60–90m; not a calibrated profit probability.\n")
            append("Momentum: 1m ${features.return1mPercent.percent()} · 3m ${features.return3mPercent.percent()} · ")
            append("5m ${features.return5mPercent.percent()} · from VWAP ${features.vwapDistancePercent.percent()}\n")
            append("Recent pullback ${drawdown.percent()} · volume reliability ${"%.0f%%".format(reliableVolumeShare * 100)} · ")
            append("coverage ${"%.0f%%".format(coverage.coerceIn(0.0, 1.0) * 100)} · confidence $confidence%")
            if (calibration != null && calibration.samples > 0) append(
                "\nHistorical ${calibration.horizonMinutes}m safety: ${"%.0f%%".format(calibration.probability * 100)} " +
                    "(${calibration.samples} samples across ${calibration.distinctDays} days; " +
                    "drawdown limit ${calibration.maximumAcceptableDrawdownPercent.percent()})"
            )
        }
        return ShortTermSafetyAssessment(score, confidence, label, details)
    }

    private fun unavailable() = ShortTermSafetyAssessment(-1, 0, "Unavailable", "No intraday bars")

    private fun isRegularSession(symbol: String, time: LocalTime): Boolean {
        val open = if (symbol.contains('.')) LocalTime.of(9, 0) else LocalTime.of(9, 30)
        val close = if (symbol.contains('.')) LocalTime.of(17, 30) else LocalTime.of(16, 0)
        return !time.isBefore(open) && time.isBefore(close)
    }

    private fun negativeRisk(value: Double, free: Double, range: Double): Double =
        if (!value.isFinite()) 0.35 else ((-value - free) / range).coerceIn(0.0, 1.0)

    private fun Double.percent(): String = if (isFinite()) "%+.2f%%".format(this) else "n/a"

    private const val RECENT_WINDOW_MINUTES = 15
    private const val MAX_FRESH_AGE_SECONDS = 180L
    private const val MIN_RELIABLE_VOLUME_SHARE = 0.70
    private const val MIN_ACTIONABLE_CONFIDENCE = 50
    private const val OFF_SESSION_CONFIDENCE_FACTOR = 0.35
    private const val MAX_CALIBRATION_WEIGHT = 0.25
}
