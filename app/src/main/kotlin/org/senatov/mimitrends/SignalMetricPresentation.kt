package org.senatov.mimitrends

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
        val percentile = result.rankingPercentile.takeIf(Double::isFinite)?.times(10.0)
            ?: ((1.0 - kotlin.math.exp(-score.coerceAtLeast(0.0) / 3.0)) * 100.0)
        val calibration = if (result.continuationProbability.isFinite() && result.predictionSource == "LOGISTIC")
            ("\nValidated logistic ${result.calibrationHorizonMinutes}m probability: %.0f%% " +
                "(model #${result.predictionModelVersion}, ${result.predictionSamples} training samples).")
                .format(result.continuationProbability * 100.0)
        else if (result.continuationProbability.isFinite())
            "\nEmpirical ${result.calibrationHorizonMinutes}m outcome available: %.0f%% after estimated friction (%d episodes)."
                .format(result.continuationProbability * 100.0, result.calibrationSamples)
        else "\nContinuation calibration: insufficient independent episodes (${result.calibrationSamples}/12)."
        return SignalMetric("%.0f%%".format(percentile), anomalyColor(percentile), if (score >= 4.0) 600 else 500,
            "Anomaly percentile: %.0f%%\nRaw anomaly score: %.2f\nMeasures rarity, confirmation, candle quality and freshness.\n".format(percentile, score) +
                "This is not a buy/sell recommendation and does not predict direction.%s"
                .format(calibration))
    }

    fun outcome(result: ScanResult): SignalMetric {
        if (!result.medianNetReturnPercent.isFinite() && result.predictionSource == "LOGISTIC") {
            val probability = result.continuationProbability * 100.0
            return SignalMetric("Model %.0f%%".format(probability), modelColor(probability), 500,
                "Validated logistic probability of a positive ${result.calibrationHorizonMinutes}-minute " +
                    "directional return after 0.20%% friction: %.0f%%\nModel #%d · training samples: %d\n".format(
                        probability, result.predictionModelVersion, result.predictionSamples) +
                    "Historical return distribution is still being collected.")
        }
        if (!result.medianNetReturnPercent.isFinite()) {
            if (result.continuationProbability.isFinite()) {
                val probability = result.continuationProbability * 100.0
                return SignalMetric("Beta %.0f%%".format(probability), modelColor(probability), 500,
                    "Preliminary ${result.calibrationHorizonMinutes}-minute probability after 0.20%% friction: %.0f%%\n".format(probability) +
                        "Independent episodes: ${result.calibrationSamples}. The sample is not yet representative; " +
                        "this estimate can change sharply and is not a validated model.")
            }
            return SignalMetric("Collecting", "#1f2933", 400,
                "Need at least 12 independent ${result.calibrationHorizonMinutes}-minute episodes; " +
                    "currently ${result.calibrationSamples}. Nearby repeated scans count as one episode.")
        }
        val probability = result.continuationProbability * 100.0
        val median = result.medianNetReturnPercent
        val color = when {
            median > 0.0 && result.continuationLowerBound >= 0.50 -> "#137b50"
            median < 0.0 && result.continuationUpperBound <= 0.50 -> "#b23b48"
            else -> "#9a6717"
        }
        val excursions = if (result.medianFavorableExcursionPercent.isFinite())
            "\nMedian favorable excursion: %+.2f%%\nMedian adverse excursion: %+.2f%%"
                .format(result.medianFavorableExcursionPercent, result.medianAdverseExcursionPercent)
        else "\nExcursion history is still being collected."
        val probabilityLine = if (result.predictionSource == "LOGISTIC")
            "Validated logistic probability after friction: %.0f%% (model #%d, %d training samples)\n".format(
                probability, result.predictionModelVersion, result.predictionSamples)
        else "Profitable after 0.20%% estimated friction: %.0f%% (95%% interval %.0f–%.0f%%)\n".format(
            probability, result.continuationLowerBound * 100.0, result.continuationUpperBound * 100.0)
        val distribution = ("Median net directional return at ${result.calibrationHorizonMinutes}m: %+.2f%%\n" +
            "Middle 50%%: %+.2f%% to %+.2f%%\n").format(
            median, result.lowerQuartileNetReturnPercent, result.upperQuartileNetReturnPercent)
        val details = distribution + probabilityLine +
            "Independent episodes: %d%s".format(result.calibrationSamples, excursions)
        return SignalMetric("%+.2f%% · %.0f%%".format(median, probability), color, 500, details)
    }

    fun outcomeSeverity(result: ScanResult): Double =
        result.continuationProbability.takeIf(Double::isFinite)?.times(100.0)
            ?: result.medianNetReturnPercent.takeIf(Double::isFinite)
            ?: Double.NEGATIVE_INFINITY

    fun priceAction(result: ScanResult): SignalMetric {
        val arrow = directionArrow(result)
        if (result.signalSource.startsWith("Oversold decline")) {
            return SignalMetric(arrows(result), "#9a6717", 600,
                "Exceptional selloff near its current low. A bottom has not been confirmed; wait for a higher low and positive recovery slope.")
        }
        if (result.signalSource.contains("wait for pullback")) {
            return SignalMetric(arrows(result), "#9a6717", 600,
                "The rising structure remains valid, but price is extended above its earlier path or has stalled near a local high. " +
                    "Entry timing is currently unfavorable; this is a watch state, not a buy signal.")
        }
        if (!result.priceAnomaly.isFinite() && !result.rangeAnomaly.isFinite()) {
            return SignalMetric(arrows(result), "#17365f", 500,
                "Persistent price trend over ${result.signalWindowLabel}; no single exceptional candle.")
        }
        val jump = result.priceAnomaly.finiteOrZero()
        val range = result.rangeAnomaly.finiteOrZero()
        val (label, color) = when {
            jump >= 6.0 && range >= 6.0 -> "Exceptional move $arrow" to Level.EXTREME.color
            range >= jump * 1.5 && range >= 3.5 -> "Volatile / unstable" to "#9a6717"
            jump >= 4.0 -> "Rare impulse $arrow" to if (arrow == "↑") "#137b50" else "#b23b48"
            else -> "Elevated move $arrow" to Level.NOTABLE.color
        }
        return SignalMetric(arrows(result), color, if (jump >= 4.0 || range >= 5.0) 600 else 500,
            "$label\nPrice jump: %.2fσ\nFull candle range: %.2fσ\n10-minute move: %+.2f%%".format(jump, range, result.windowChangePercent))
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
        return SignalMetric(label, level.color, if (level in setOf(Level.EXTREME, Level.STRONG)) 600 else 500,
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
    private fun modelColor(probability: Double) = when {
        probability >= 70.0 -> "#0b6b43"
        probability >= 60.0 -> "#2e8b57"
        probability >= 50.0 -> "#9a6717"
        probability >= 40.0 -> "#c06b22"
        else -> "#b23b48"
    }
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
