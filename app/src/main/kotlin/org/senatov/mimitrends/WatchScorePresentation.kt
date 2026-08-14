package org.senatov.mimitrends

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.MarketTimeZone
import java.time.Instant
import java.time.LocalTime

internal data class WatchScore(val value: Int, val color: String, val details: String) {
    val category: String get() = when {
        value >= BUY_THRESHOLD -> "buy"
        value >= WAIT_THRESHOLD -> "wait"
        else -> "avoid"
    }
    val label: String get() = "$value% ($category)"

    private companion object {
        const val BUY_THRESHOLD = 67
        const val WAIT_THRESHOLD = 35
    }
}

internal object WatchScorePresentation {
    fun calculate(result: ScanResult): WatchScore {
        val structure = structureScore(result.signalSource)
        val strength = historicalStrength(result)
        val freshness = exp(-result.signalAgeMinutes.coerceAtLeast(0) / FRESHNESS_DECAY_MINUTES)
        val timing = entryTiming(result)
        val volume = volumeConfirmation(result)
        val outcome = outcomeEvidence(result)
        val components = Components(structure, strength, freshness, timing, volume, outcome)
        var value = (components.weightedTotal * 100.0).roundToInt().coerceIn(0, 100)

        val volumeConfirmed = hasSupportiveVolume(result)
        val outcomeConfirmed = hasRepresentativeOutcome(result)
        if (!volumeConfirmed && !outcomeConfirmed) value = value.coerceAtMost(59)
        val spread = executableSpreadPercent(result)
        if (spread == null) value = value.coerceAtMost(59)
        else if (spread >= PROHIBITIVE_SPREAD_PERCENT) value = value.coerceAtMost(29)
        else if (spread >= EXPENSIVE_SPREAD_PERCENT) value = value.coerceAtMost(49)
        if (result.lowerQuartileNetReturnPercent.isFinite() && result.lowerQuartileNetReturnPercent <= 0.0) {
            value = value.coerceAtMost(59)
        }
        if (outcomeConfirmed && result.continuationLowerBound < MIN_BUY_OUTCOME_LOWER_BOUND) {
            value = value.coerceAtMost(59)
        }
        if (isBullish(result) && result.recentThreeMinutePercent.isFinite()) {
            if (result.recentThreeMinutePercent <= NEGATIVE_THREE_MINUTE_PERCENT) value = value.coerceAtMost(29)
            else if (result.recentThreeMinutePercent <= 0.0) value = value.coerceAtMost(49)
        }
        if (isBullish(result) && result.recentFiveMinutePercent.isFinite() &&
            result.recentFiveMinutePercent <= NEGATIVE_FIVE_MINUTE_PERCENT) value = value.coerceAtMost(29)
        if (result.recentDirectionChanges >= CYCLICAL_DIRECTION_CHANGES &&
            result.recentFiveMinutePercent.isFinite() &&
            kotlin.math.abs(result.recentFiveMinutePercent) <= CYCLICAL_MAX_NET_MOVE_PERCENT) {
            value = value.coerceAtMost(49)
        }
        if (isEarlyBullishReversal(result)) value = value.coerceAtMost(34)
        if (components.timing < POOR_TIMING_THRESHOLD) value = value.coerceAtMost(49)
        else if (components.timing < FAIR_TIMING_THRESHOLD) value = value.coerceAtMost(59)
        if (result.signalSource.contains("wait for pullback", true)) value = value.coerceAtMost(59)
        if (result.signalAgeMinutes >= STALE_SIGNAL_MINUTES) value = value.coerceAtMost(29)
        else if (result.signalAgeMinutes >= AGING_SIGNAL_MINUTES) value = value.coerceAtMost(59)
        if (result.signalSource.startsWith("Oversold decline", true) ||
            result.signalSource.contains("bottom unconfirmed", true) || result.signalSource.contains('↓')) {
            value = value.coerceAtMost(29)
        }

        val color = when {
            value >= 67 -> "#137b50"
            value >= 35 -> "#b26012"
            else -> "#b23b48"
        }
        return WatchScore(value, color, details(result, value, components, volumeConfirmed, outcomeConfirmed, spread))
    }

    private fun structureScore(source: String): Double = when {
        source.startsWith("Recovery breakout", true) -> 0.78
        source.startsWith("V-Reversal", true) -> 0.74
        source.startsWith("Early recovery", true) -> 0.68
        source.startsWith("Recovery rise", true) -> 0.67
        source.startsWith("Steady rise", true) -> 0.65
        source.startsWith("Momentum", true) -> 0.62
        source.startsWith("Impulse", true) -> 0.58
        source.startsWith("Oversold decline", true) -> 0.20
        else -> 0.50
    }

    private fun historicalStrength(result: ScanResult): Double =
        if (result.rankingPercentile.isFinite()) (result.rankingPercentile / 10.0).coerceIn(0.0, 1.0)
        else result.anomalyScore.coerceAtLeast(0.0).let { it / (it + ANOMALY_HALF_SATURATION) }

    private fun entryTiming(result: ScanResult): Double {
        val excessMove = (result.windowChangePercent - FREE_ENTRY_MOVE_PERCENT).coerceAtLeast(0.0)
        var score = exp(-excessMove / CHASE_DECAY_PERCENT)
        if (result.signalSource.contains("extended", true)) score *= 0.55
        if (result.signalSource.contains("cooling", true)) score *= 0.45
        if (result.signalSource.contains("relaxed", true)) score *= 0.75
        return score.coerceIn(0.0, 1.0)
    }

    private fun volumeConfirmation(result: ScanResult): Double {
        val relative = result.relativeVolume.takeIf(Double::isFinite)?.coerceAtLeast(0.0)?.let {
            (ln(it.coerceAtLeast(1.0)) / ln(3.0)).coerceIn(0.0, 1.0)
        }
        val anomaly = result.volumeAnomaly.takeIf(Double::isFinite)?.let { (it / 5.0).coerceIn(0.0, 1.0) }
        return listOfNotNull(relative, anomaly).maxOrNull() ?: MISSING_EVIDENCE_SCORE
    }

    private fun outcomeEvidence(result: ScanResult): Double =
        if (hasRepresentativeOutcome(result)) {
            result.continuationLowerBound.takeIf(Double::isFinite)
                ?: result.continuationProbability.coerceIn(0.0, 1.0)
        }
        else MISSING_EVIDENCE_SCORE

    private fun hasSupportiveVolume(result: ScanResult): Boolean =
        result.relativeVolume.takeIf(Double::isFinite)?.let { it >= MIN_SUPPORTIVE_RELATIVE_VOLUME } == true ||
            result.volumeAnomaly.takeIf(Double::isFinite)?.let { it >= MIN_SUPPORTIVE_VOLUME_Z } == true

    private fun hasRepresentativeOutcome(result: ScanResult): Boolean =
        result.continuationProbability.isFinite() && (
            result.continuationLowerBound.isFinite() && result.continuationUpperBound.isFinite() ||
                result.predictionSource == "LOGISTIC" && result.predictionSamples >= MIN_MODEL_SAMPLES
            )

    private fun details(
        result: ScanResult,
        value: Int,
        components: Components,
        volumeConfirmed: Boolean,
        outcomeConfirmed: Boolean,
        spreadPercent: Double?
    ): String = "Entry readiness: $value% (heuristic, not profit probability).\n" +
        "Structure %.0f%% · strength %.0f%% · freshness %.0f%% · timing %.0f%% · volume %.0f%% · outcomes %.0f%%\n".format(
            components.structure * 100, components.strength * 100, components.freshness * 100,
            components.timing * 100, components.volume * 100, components.outcome * 100
        ) + "Volume confirmed: ${if (volumeConfirmed) "yes" else "no"} · representative outcomes: " +
        (if (outcomeConfirmed) "yes" else "no") + " · executable spread: " +
        (spreadPercent?.let { "%.2f%%".format(it) } ?: "unavailable") + recentDynamicsText(result)

    private fun recentDynamicsText(result: ScanResult): String {
        val three = result.recentThreeMinutePercent.takeIf(Double::isFinite)?.let { "%+.2f%%".format(it) } ?: "n/a"
        val five = result.recentFiveMinutePercent.takeIf(Double::isFinite)?.let { "%+.2f%%".format(it) } ?: "n/a"
        return "\nLatest movement: 3m $three · 5m $five · direction changes ${result.recentDirectionChanges}"
    }

    private fun executableSpreadPercent(result: ScanResult): Double? {
        if (!result.bidPrice.isFinite() || !result.askPrice.isFinite() || result.askPrice < result.bidPrice) return null
        val midpoint = (result.askPrice + result.bidPrice) / 2.0
        return if (midpoint > 0.0) (result.askPrice - result.bidPrice) / midpoint * 100.0 else null
    }

    private fun isEarlyBullishReversal(result: ScanResult): Boolean {
        if (!result.signalSource.startsWith("V-Reversal ↑", true)) return false
        val local = Instant.ofEpochMilli(result.signalEpochMillis).atZone(MarketTimeZone.forSymbol(result.symbol))
        val open = if (result.symbol.contains('.')) LocalTime.of(9, 0) else LocalTime.of(9, 30)
        return local.toLocalTime().isBefore(open.plusMinutes(EARLY_REVERSAL_MINUTES))
    }

    private fun isBullish(result: ScanResult): Boolean = '↑' in result.signalSource

    private data class Components(
        val structure: Double,
        val strength: Double,
        val freshness: Double,
        val timing: Double,
        val volume: Double,
        val outcome: Double
    ) {
        val weightedTotal: Double get() = structure * 0.30 + strength * 0.20 + freshness * 0.15 +
            timing * 0.15 + volume * 0.10 + outcome * 0.10
    }

    private const val ANOMALY_HALF_SATURATION = 3.0
    private const val FRESHNESS_DECAY_MINUTES = 10.0
    private const val FREE_ENTRY_MOVE_PERCENT = 0.50
    private const val CHASE_DECAY_PERCENT = 0.75
    private const val MISSING_EVIDENCE_SCORE = 0.40
    private const val MIN_SUPPORTIVE_RELATIVE_VOLUME = 1.20
    private const val MIN_SUPPORTIVE_VOLUME_Z = 1.0
    private const val MIN_MODEL_SAMPLES = 30
    private const val POOR_TIMING_THRESHOLD = 0.50
    private const val FAIR_TIMING_THRESHOLD = 0.70
    private const val AGING_SIGNAL_MINUTES = 10
    private const val STALE_SIGNAL_MINUTES = 30
    private const val EARLY_REVERSAL_MINUTES = 30L
    private const val EXPENSIVE_SPREAD_PERCENT = 0.12
    private const val PROHIBITIVE_SPREAD_PERCENT = 0.30
    private const val MIN_BUY_OUTCOME_LOWER_BOUND = 0.50
    private const val NEGATIVE_THREE_MINUTE_PERCENT = -0.05
    private const val NEGATIVE_FIVE_MINUTE_PERCENT = -0.08
    private const val CYCLICAL_DIRECTION_CHANGES = 3
    private const val CYCLICAL_MAX_NET_MOVE_PERCENT = 0.15
}
