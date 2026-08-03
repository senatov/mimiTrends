package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

internal data class SignalMetric(
    val label: String,
    val color: String,
    val weight: Int,
    val details: String
)

internal object SignalMetricPresentation {
    fun strength(result: ScanResult): SignalMetric {
        val score = result.anomalyScore
        val level = when {
            score >= 6.0 -> Level.EXTREME
            score >= 4.0 -> Level.STRONG
            score >= 2.5 -> Level.NOTABLE
            else -> Level.WATCH
        }
        return SignalMetric(level.label, level.color, if (score >= 4.0) 600 else 500,
            "Composite signal strength: %.2f×\nIncludes price anomaly, volume confirmation, candle quality and freshness.".format(score))
    }

    fun priceAction(result: ScanResult): SignalMetric {
        val arrow = directionArrow(result)
        if (!result.priceAnomaly.isFinite() && !result.rangeAnomaly.isFinite()) {
            return SignalMetric("Steady trend $arrow", "#3f6682", 500,
                "Persistent price trend over ${result.signalWindowLabel}; no single exceptional candle.")
        }
        val jump = result.priceAnomaly.finiteOrZero()
        val range = result.rangeAnomaly.finiteOrZero()
        val (label, color) = when {
            jump >= 6.0 && range >= 6.0 -> "Extreme impulse $arrow" to Level.EXTREME.color
            range >= jump * 1.5 && range >= 3.5 -> "Volatile / unstable" to "#9a6717"
            jump >= 4.0 -> "Strong impulse $arrow" to if (arrow == "↑") "#137b50" else "#b23b48"
            else -> "Elevated move $arrow" to Level.NOTABLE.color
        }
        return SignalMetric(label, color, if (jump >= 4.0 || range >= 5.0) 600 else 500,
            "Price jump: %.2fσ\nFull candle range: %.2fσ\n10-minute move: %+.2f%%".format(jump, range, result.windowChangePercent))
    }

    fun volume(result: ScanResult): SignalMetric {
        val relative = result.relativeVolume.takeIf(Double::isFinite)
        val anomaly = result.volumeAnomaly.takeIf(Double::isFinite)
        if (relative == null && anomaly == null) {
            val impulse = result.signalSource.startsWith("Impulse") || result.signalSource.startsWith("Momentum")
            return SignalMetric(
                if (impulse) "Unavailable" else "Price-led",
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
        val label = relative?.let { "${level.volumeLabel} %.1f×".format(it) } ?: level.volumeLabel
        return SignalMetric(label, level.color, if (level in setOf(Level.EXTREME, Level.STRONG)) 600 else 500,
            "Relative volume: ${relative?.let { "%.2f×".format(it) } ?: "—"}\nVolume anomaly: ${anomaly?.let { "%.2fσ".format(it) } ?: "—"}")
    }

    fun priceActionSeverity(result: ScanResult): Double = maxOf(
        result.priceAnomaly.finiteOrZero() / 3.0,
        result.rangeAnomaly.finiteOrZero() / 3.5
    )

    fun volumeSeverity(result: ScanResult): Double = maxOf(
        result.volumeAnomaly.finiteOrZero() / 2.0,
        result.relativeVolume.finiteOrZero() / 1.8
    )

    private fun directionArrow(result: ScanResult) = if (result.windowChangePercent < 0) "↓" else "↑"
    private fun Double.finiteOrZero() = takeIf(Double::isFinite) ?: 0.0

    private enum class Level(val label: String, val volumeLabel: String, val color: String) {
        EXTREME("Extreme", "Extreme", "#a92f3d"),
        STRONG("Strong", "Strong", "#b26012"),
        NOTABLE("Notable", "Elevated", "#526f8a"),
        WATCH("Watch", "Normal", "#707981")
    }
}
