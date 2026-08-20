package org.senatov.mimitrends

import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.ScanResult
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.max

internal data class EntryQualityInput(
    val price: Double,
    val bid: Double,
    val ask: Double,
    val return1mPercent: Double,
    val return3mPercent: Double,
    val return5mPercent: Double,
    val volatility30mPercent: Double,
    val vwapDistancePercent: Double,
    val sessionHighDistancePercent: Double
)

internal data class EntryQualityAssessment(
    val score: Int,
    val confidence: Int,
    val label: String,
    val cooldownMinutes: Int,
    val details: String
)

internal object EntryQualityModel {
    fun assess(input: EntryQualityInput): EntryQualityAssessment {
        val spread = spreadPercent(input.bid, input.ask)
        val nearHigh = input.sessionHighDistancePercent.takeIf(Double::isFinite)?.let {
            ((it + NEAR_HIGH_DISTANCE_PERCENT) / NEAR_HIGH_DISTANCE_PERCENT).coerceIn(0.0, 1.0)
        }
        val vwapExtension = input.vwapDistancePercent.takeIf(Double::isFinite)?.let {
            ((it - FREE_VWAP_EXTENSION_PERCENT) / VWAP_PENALTY_RANGE_PERCENT).coerceIn(0.0, 1.0)
        }
        val acceleration = acceleration(input.return1mPercent, input.return3mPercent)
        val impulse = max(
            normalizedPositive(input.return3mPercent, FREE_RETURN_3M_PERCENT, RETURN_3M_PENALTY_RANGE),
            normalizedPositive(input.return5mPercent, FREE_RETURN_5M_PERCENT, RETURN_5M_PENALTY_RANGE)
        )
        val accelerationPenalty = normalizedPositive(
            acceleration, FREE_ACCELERATION_PERCENT, ACCELERATION_PENALTY_RANGE
        )
        val volatilityScale = input.volatility30mPercent.takeIf(Double::isFinite)
            ?.coerceAtLeast(MIN_VOLATILITY_PERCENT) ?: DEFAULT_VOLATILITY_PERCENT
        val volatilityAdjustedImpulse = (impulse / (1.0 + volatilityScale / 0.45)).coerceIn(0.0, 1.0)
        val spreadPenalty = spread?.let {
            ((it - FREE_SPREAD_PERCENT) / SPREAD_PENALTY_RANGE_PERCENT).coerceIn(0.0, 1.0)
        }
        val quotePositionPenalty = quotePositionPenalty(input)
        val chaseRisk = maxOf(
            (nearHigh ?: 0.0) * max(volatilityAdjustedImpulse, accelerationPenalty),
            (vwapExtension ?: 0.0) * 0.85,
            accelerationPenalty * 0.75
        )
        val rawScore = 100.0 -
            34.0 * chaseRisk -
            23.0 * (spreadPenalty ?: 0.0) -
            16.0 * quotePositionPenalty -
            12.0 * volatilityAdjustedImpulse -
            9.0 * (nearHigh ?: 0.0)
        val available = listOf(
            spread, nearHigh, vwapExtension,
            input.return1mPercent.takeIf(Double::isFinite),
            input.return3mPercent.takeIf(Double::isFinite),
            input.return5mPercent.takeIf(Double::isFinite),
            input.volatility30mPercent.takeIf(Double::isFinite)
        ).count { it != null }
        val confidence = (available * 100 / 7).coerceIn(0, 100)
        val reliability = 0.25 + 0.75 * confidence / 100.0
        val cooldown = cooldownMinutes(input, chaseRisk, accelerationPenalty, vwapExtension ?: 0.0)
        val score = (50.0 + (rawScore - 50.0) * reliability).toInt()
            .coerceIn(0, if (cooldown > 0) MAX_COOLDOWN_SCORE else 100)
        val label = when {
            confidence < MIN_ACTIONABLE_CONFIDENCE -> "Limited data"
            cooldown > 0 -> "Wait for pullback"
            score >= 72 -> "Good entry"
            score >= 52 -> "Fair entry"
            else -> "Poor entry"
        }
        val details = buildString {
            append("Spread: ${spread?.let { "%.2f%%".format(it) } ?: "n/a"}")
            append(" · from session high: ${input.sessionHighDistancePercent.metricPercent()}")
            append(" · from VWAP: ${input.vwapDistancePercent.metricPercent()}\n")
            append("Momentum: 1m ${input.return1mPercent.metricPercent()} · 3m ${input.return3mPercent.metricPercent()} · ")
            append("5m ${input.return5mPercent.metricPercent()} · acceleration ${acceleration.metricPercent()}\n")
            append("Chase risk: ${"%.0f%%".format(chaseRisk * 100.0)} · ")
            append("30m volatility: ${input.volatility30mPercent.metricPercent()} · confidence $confidence%")
            if (cooldown > 0) append(" · cooldown ${cooldown}m")
        }
        return EntryQualityAssessment(score, confidence, label, cooldown, details)
    }

    fun input(result: ScanResult, features: ResearchFeatures): EntryQualityInput = EntryQualityInput(
        price = result.price,
        bid = result.bidPrice,
        ask = result.askPrice,
        return1mPercent = features.return1mPercent,
        return3mPercent = features.return3mPercent,
        return5mPercent = features.return5mPercent,
        volatility30mPercent = features.realizedVolatility30m,
        vwapDistancePercent = features.vwapDistancePercent,
        sessionHighDistancePercent = features.sessionHighDistancePercent
    )

    private fun cooldownMinutes(
        input: EntryQualityInput,
        chaseRisk: Double,
        accelerationPenalty: Double,
        vwapExtension: Double
    ): Int {
        val momentum = max(
            input.return3mPercent.takeIf(Double::isFinite) ?: 0.0,
            input.return5mPercent.takeIf(Double::isFinite) ?: 0.0
        )
        if (chaseRisk < COOLDOWN_RISK && accelerationPenalty < COOLDOWN_ACCELERATION &&
            vwapExtension < COOLDOWN_VWAP_EXTENSION) return 0
        return ceil(MIN_COOLDOWN_MINUTES + max(momentum, 0.0) * COOLDOWN_MINUTES_PER_PERCENT)
            .toInt().coerceIn(MIN_COOLDOWN_MINUTES, MAX_COOLDOWN_MINUTES)
    }

    private fun acceleration(oneMinute: Double, threeMinute: Double): Double =
        if (oneMinute.isFinite() && threeMinute.isFinite()) oneMinute - threeMinute / 3.0 else Double.NaN

    private fun quotePositionPenalty(input: EntryQualityInput): Double {
        if (!input.price.isFinite() || !input.bid.isFinite() || !input.ask.isFinite() ||
            input.ask <= input.bid) return 0.0
        val midpoint = (input.ask + input.bid) / 2.0
        if (midpoint <= 0.0 || abs(input.price / midpoint - 1.0) > MAX_QUOTE_PRICE_DEVIATION) return 0.0
        return ((input.price - input.bid) / (input.ask - input.bid)).coerceIn(0.0, 1.0)
    }

    private fun spreadPercent(bid: Double, ask: Double): Double? {
        if (!bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid) return null
        return (ask - bid) / ((ask + bid) / 2.0) * 100.0
    }

    private fun normalizedPositive(value: Double, free: Double, range: Double): Double =
        if (!value.isFinite()) 0.0 else ((value - free) / range).coerceIn(0.0, 1.0)

    private fun Double.metricPercent(): String = if (isFinite()) "%+.2f%%".format(this) else "n/a"

    private const val FREE_SPREAD_PERCENT = 0.05
    private const val SPREAD_PENALTY_RANGE_PERCENT = 0.30
    private const val NEAR_HIGH_DISTANCE_PERCENT = 0.35
    private const val FREE_VWAP_EXTENSION_PERCENT = 0.20
    private const val VWAP_PENALTY_RANGE_PERCENT = 1.20
    private const val FREE_RETURN_3M_PERCENT = 0.12
    private const val RETURN_3M_PENALTY_RANGE = 0.70
    private const val FREE_RETURN_5M_PERCENT = 0.20
    private const val RETURN_5M_PENALTY_RANGE = 1.00
    private const val FREE_ACCELERATION_PERCENT = 0.04
    private const val ACCELERATION_PENALTY_RANGE = 0.30
    private const val MIN_VOLATILITY_PERCENT = 0.03
    private const val DEFAULT_VOLATILITY_PERCENT = 0.25
    private const val COOLDOWN_RISK = 0.52
    private const val COOLDOWN_ACCELERATION = 0.60
    private const val COOLDOWN_VWAP_EXTENSION = 0.72
    private const val MIN_COOLDOWN_MINUTES = 3
    private const val MAX_COOLDOWN_MINUTES = 15
    private const val COOLDOWN_MINUTES_PER_PERCENT = 5.0
    private const val MAX_COOLDOWN_SCORE = 45
    private const val MIN_ACTIONABLE_CONFIDENCE = 43
    private const val MAX_QUOTE_PRICE_DEVIATION = 0.10
}
