package org.senatov.mimitrends

import kotlin.math.roundToInt
import org.senatov.mimitrends.model.ScanResult

internal data class WatchScore(val value: Int, val color: String, val details: String) {
    val label: String get() = when (value) {
        in 7..10 -> "BUY ${value * 10}%"
        in 4..6 -> "WAIT ${value * 10}%"
        else -> "AVOID ${value * 10}%"
    }
}

internal object WatchScorePresentation {
    fun calculate(result: ScanResult): WatchScore {
        var raw = 3.5 + result.anomalyScore.coerceIn(0.0, 6.0) / 3.0
        raw += when {
            result.signalSource.startsWith("Steady rise", true) -> 1.5
            result.signalSource.startsWith("Recovery", true) -> 1.0
            result.signalSource.startsWith("Impulse", true) -> 0.5
            else -> 0.0
        }
        if (result.signalSource.contains('↓')) raw -= 1.5
        if (result.signalSource.contains("wait for pullback", true)) raw -= 1.5
        if (result.signalSource.contains("cooling", true)) raw -= 2.0
        if (result.signalSource.contains("relaxed", true)) raw -= 0.75
        raw -= (result.signalAgeMinutes / 30.0).coerceIn(0.0, 1.5)
        if (result.calibrationSamples >= 5 && result.continuationProbability.isFinite()) {
            raw += ((result.continuationProbability - 0.5) * 4.0).coerceIn(-1.5, 1.5)
        }
        if (result.medianNetReturnPercent.isFinite()) {
            raw += (result.medianNetReturnPercent * 0.5).coerceIn(-0.75, 0.75)
        }
        if (result.relativeVolume.isFinite()) raw += ((result.relativeVolume - 1.0) / 4.0).coerceIn(0.0, 0.5)
        var value = raw.roundToInt().coerceIn(1, 10)
        val volumeConfirmed = result.relativeVolume.isFinite() || result.volumeAnomaly.isFinite()
        val outcomeConfirmed = result.calibrationSamples >= MIN_CALIBRATION_SAMPLES &&
            result.continuationProbability.isFinite() && result.continuationProbability >= MIN_BUY_PROBABILITY
        if (!volumeConfirmed && !outcomeConfirmed) value = value.coerceAtMost(6)
        if (result.signalSource.startsWith("Steady rise", true) &&
            result.windowChangePercent >= MAX_UNCONFIRMED_ENTRY_MOVE_PERCENT && !volumeConfirmed) {
            value = value.coerceAtMost(6)
        }
        if (result.signalSource.contains("wait for pullback", true)) value = value.coerceAtMost(6)
        if (result.signalSource.startsWith("Oversold decline", true)) value = value.coerceAtMost(3)
        if (result.signalSource.contains("bottom unconfirmed", true)) value = value.coerceAtMost(3)
        val color = when (value) {
            in 1..3 -> "#b23b48"
            in 4..6 -> "#b26012"
            else -> "#137b50"
        }
        return WatchScore(value, color,
            "Entry readiness: ${value * 10}%. Combines trend structure, signal strength, entry timing, freshness, " +
                "volume and calibrated outcomes. This is a heuristic score, not a probability or financial advice.")
    }

    private const val MIN_CALIBRATION_SAMPLES = 12
    private const val MIN_BUY_PROBABILITY = 0.55
    private const val MAX_UNCONFIRMED_ENTRY_MOVE_PERCENT = 0.75
}
