package org.senatov.mimitrends.signals

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.senatov.mimitrends.model.ScanResult

internal data class SignalMetric(
    val label: String,
    val color: String,
    val weight: Int,
    val details: String
)

internal object SignalMetricPresentation {
    fun entryQuality(result: ScanResult): SignalMetric {
        if (result.entryQualityScore < 0) return SignalMetric(
            "—", "#52606d", 400, "Entry-quality inputs are unavailable for this result."
        )
        val color = when {
            result.entryQualityScore >= 72 -> "#137b50"
            result.entryQualityScore >= 52 -> "#9a6717"
            else -> "#b23b48"
        }
        val shortLabel = when {
            result.entryQualityConfidence < 43 -> "Limited"
            result.entryCooldownMinutes > 0 -> "Wait"
            result.entryQualityScore >= 72 -> "Good"
            result.entryQualityScore >= 52 -> "Fair"
            else -> "Poor"
        }
        return SignalMetric(
            "${result.entryQualityScore}% · $shortLabel",
            color,
            if (result.entryQualityScore < 52) 600 else 500,
            "${result.entryQualityLabel}\n${result.entryQualityDetails}"
        )
    }

    fun entryQualitySeverity(result: ScanResult): Double = result.entryQualityScore.toDouble()

    fun strength(result: ScanResult): SignalMetric {
        val score = result.anomalyScore
        val intensity = (1.0 - kotlin.math.exp(-score.coerceAtLeast(0.0) / 3.0)) * 100.0
        return SignalMetric(
            "%.1f".format(score), anomalyColor(intensity), if (score >= 4.0) 600 else 500,
            "Composite anomaly score: %.2f\nMeasures rarity, confirmation, candle quality and freshness.\n".format(score) +
                    "This is not a buy/sell recommendation and does not predict direction."
        )
    }

    fun priceAction(result: ScanResult): SignalMetric {
        val arrow = directionArrow(result)
        if (result.signalSource.startsWith("Oversold decline")) {
            return SignalMetric(
                arrows(result), "#9a6717", 600,
                "Exceptional selloff near its current low. A bottom has not been confirmed; wait for a higher low and positive recovery slope."
            )
        }
        if (result.signalSource.contains("wait for pullback")) {
            return SignalMetric(
                arrows(result), "#9a6717", 600,
                "The rising structure remains valid, but price is extended above its earlier path or has stalled near a local high. " +
                        "Entry timing is currently unfavorable; this is a watch state, not a buy signal."
            )
        }
        if (!result.priceAnomaly.isFinite() && !result.rangeAnomaly.isFinite()) {
            return SignalMetric(
                arrows(result), "#17365f", 500,
                "Persistent price trend over ${result.signalWindowLabel}; no single exceptional candle."
            )
        }
        val jump = result.priceAnomaly.finiteOrZero()
        val range = result.rangeAnomaly.finiteOrZero()
        val (label, color) = when {
            jump >= 6.0 && range >= 6.0 -> "Exceptional move $arrow" to Level.EXTREME.color
            range >= jump * 1.5 && range >= 3.5 -> "Volatile / unstable" to "#9a6717"
            jump >= 4.0 -> "Rare impulse $arrow" to if (arrow == "↑") "#137b50" else "#b23b48"
            else -> "Elevated move $arrow" to Level.NOTABLE.color
        }
        return SignalMetric(
            arrows(result), color, if (jump >= 4.0 || range >= 5.0) 600 else 500,
            "$label\nPrice jump: %.2fσ\nFull candle range: %.2fσ\n10-minute move: %+.2f%%".format(
                jump,
                range,
                result.windowChangePercent
            )
        )
    }

    fun volume(result: ScanResult): SignalMetric {
        val relative = result.relativeVolume.takeIf(Double::isFinite)
        val anomaly = result.volumeAnomaly.takeIf(Double::isFinite)
        if (relative == null && anomaly == null) {
            val impulse = result.signalSource.startsWith("Impulse") ||
                    result.signalSource.startsWith("Momentum") || result.signalSource.startsWith("V-Reversal")
            return SignalMetric(
                "—",
                Level.WATCH.color,
                400,
                if (impulse) "No reliable positive volume was reported for the signal candle."
                else "Trend signal without a single-candle volume anomaly."
            )
        }
        val level = when {
            (relative ?: 0.0) >= 5.0 || (anomaly ?: 0.0) >= 5.0 -> Level.EXTREME
            (relative ?: 0.0) >= 3.0 || (anomaly ?: 0.0) >= 3.0 -> Level.STRONG
            (relative ?: 0.0) >= 1.8 || (anomaly ?: 0.0) >= 2.0 -> Level.NOTABLE
            else -> Level.WATCH
        }
        val label = listOfNotNull(
            relative?.let { "%.1f×".format(it) }, anomaly?.let { "%.1fσ".format(it) }
        ).joinToString(" / ")
        return SignalMetric(
            label, level.color, if (level in setOf(Level.EXTREME, Level.STRONG)) 600 else 500,
            "Relative volume: ${relative?.let { "%.2f×".format(it) } ?: "—"}\nVolume anomaly: ${anomaly?.let { "%.2fσ".format(it) } ?: "—"}")
    }

    fun priceActionSeverity(result: ScanResult): Double = maxOf(
        result.priceAnomaly.finiteOrZero() / 3.0,
        result.rangeAnomaly.finiteOrZero() / 3.5
    )

    private fun arrows(result: ScanResult): String {
        val count = kotlin.math.ceil(priceActionSeverity(result)).toInt().coerceIn(1, 5)
        return directionArrow(result).repeat(count)
    }

    fun volumeSeverity(result: ScanResult): Double = maxOf(
        result.volumeAnomaly.finiteOrZero() / 2.0,
        result.relativeVolume.finiteOrZero() / 1.8
    )

    private fun directionArrow(result: ScanResult) = if (result.windowChangePercent < 0) "↓" else "↑"
    private fun anomalyColor(percentile: Double) = when {
        percentile >= 90.0 -> "#a92f3d"
        percentile >= 75.0 -> "#b26012"
        percentile >= 55.0 -> "#17365f"
        else -> "#52606d"
    }

    private fun Double.finiteOrZero() = takeIf(Double::isFinite) ?: 0.0

    private enum class Level(val anomalyLabel: String, val volumeLabel: String, val color: String) {
        EXTREME("Very high", "Extreme", "#a92f3d"),
        STRONG("High", "Strong", "#b26012"),
        NOTABLE("Moderate", "Elevated", "#17365f"),
        WATCH("Low", "Normal", "#1f2933")
    }
}