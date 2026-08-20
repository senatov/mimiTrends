package org.senatov.mimitrends

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

internal data class TrendPrice(val epochSeconds: Long, val close: Double)

internal data class MultiHorizonTrendAssessment(
    val score: Int,
    val confidence: Int,
    val label: String,
    val details: String
)

internal object MultiHorizonTrendModel {
    private data class Horizon(val sessions: Int, val label: String, val weight: Double)
    private data class Component(val horizon: Horizon, val score: Double, val returnPercent: Double)

    fun assess(prices: List<TrendPrice>): MultiHorizonTrendAssessment? {
        val daily = prices.asSequence()
            .filter { it.close.isFinite() && it.close > 0.0 }
            .sortedBy(TrendPrice::epochSeconds)
            .groupBy { it.epochSeconds / DAY_SECONDS }
            .map { (_, values) -> values.last() }
            .takeLast(MAX_SESSIONS)
        if (daily.size < MIN_SESSIONS) return null
        val logPrices = daily.map { ln(it.close) }
        val dailyReturns = logPrices.zipWithNext { left, right -> right - left }
        val components = HORIZONS.mapNotNull { horizon ->
            if (daily.size < horizon.sessions + 1) null else component(logPrices, dailyReturns, horizon)
        }
        if (components.isEmpty()) return null
        val availableWeight = components.sumOf { it.horizon.weight }
        val base = components.sumOf { it.score * it.horizon.weight } / availableWeight
        val positiveWeight = components.filter { it.returnPercent > 0.0 }.sumOf { it.horizon.weight } / availableWeight
        val agreement = (positiveWeight - 0.5) * 12.0
        val rawScore = (base + agreement).coerceIn(0.0, 100.0)
        val coverage = availableWeight / HORIZONS.sumOf(Horizon::weight)
        val sampleCoverage = (daily.size / MAX_SESSIONS.toDouble()).coerceIn(0.0, 1.0)
        val confidence = ((0.72 * coverage + 0.28 * sqrt(sampleCoverage)) * 100.0).toInt().coerceIn(0, 100)
        val reliability = 0.35 + 0.65 * confidence / 100.0
        val score = (50.0 + (rawScore - 50.0) * reliability).toInt().coerceIn(0, 100)
        val strongest = components.maxBy { it.horizon.sessions }.horizon.label
        val label = when {
            score >= 78 && positiveWeight >= 0.70 -> "broad uptrend"
            score >= 66 -> "positive trend"
            score >= 56 -> "holding positive"
            else -> "mixed"
        }
        val horizonText = components.joinToString(" · ") {
            "${it.horizon.label} ${"%+.1f%%".format(it.returnPercent)}"
        }
        return MultiHorizonTrendAssessment(score, confidence, label,
            "$horizonText\nCoverage: ${daily.size} sessions through $strongest · confidence $confidence%")
    }

    private fun component(logPrices: List<Double>, allReturns: List<Double>, horizon: Horizon): Component {
        val window = logPrices.takeLast(horizon.sessions + 1)
        val returns = allReturns.takeLast(horizon.sessions)
        val logReturn = window.last() - window.first()
        val volatility = ewmaVolatility(returns).coerceAtLeast(MIN_DAILY_VOLATILITY)
        val riskAdjusted = tanh(logReturn / (volatility * sqrt(horizon.sessions.toDouble()) * 1.6))
        val monotonicity = sampledKendallTau(window)
        val positiveShare = returns.count { it > 0.0 } / returns.size.toDouble()
        val breadth = ((positiveShare - 0.5) * 2.0).coerceIn(-1.0, 1.0)
        val drawdown = maximumDrawdown(window)
        val drawdownPenalty = (drawdown / (volatility * sqrt(horizon.sessions.toDouble()) * 2.5))
            .coerceIn(0.0, 1.0)
        val normalized = 0.52 * riskAdjusted + 0.25 * monotonicity + 0.23 * breadth - 0.24 * drawdownPenalty
        return Component(horizon, (50.0 + normalized * 50.0).coerceIn(0.0, 100.0), (exp(logReturn) - 1.0) * 100.0)
    }

    private fun ewmaVolatility(returns: List<Double>): Double {
        var variance = returns.firstOrNull()?.let { it * it } ?: return 0.0
        returns.drop(1).forEach { value -> variance = EWMA_LAMBDA * variance + (1.0 - EWMA_LAMBDA) * value * value }
        return sqrt(variance)
    }

    private fun sampledKendallTau(values: List<Double>): Double {
        val sampled = if (values.size <= MAX_TAU_POINTS) values else (0 until MAX_TAU_POINTS).map { index ->
            values[index * (values.lastIndex) / (MAX_TAU_POINTS - 1)]
        }
        var concordant = 0
        var discordant = 0
        for (left in 0 until sampled.lastIndex) for (right in left + 1..sampled.lastIndex) {
            val difference = sampled[right] - sampled[left]
            if (difference > TIE_EPSILON) concordant++ else if (difference < -TIE_EPSILON) discordant++
        }
        val pairs = concordant + discordant
        return if (pairs == 0) 0.0 else (concordant - discordant) / pairs.toDouble()
    }

    private fun maximumDrawdown(logPrices: List<Double>): Double {
        var peak = logPrices.first()
        var drawdown = 0.0
        logPrices.forEach { value ->
            peak = maxOf(peak, value)
            drawdown = maxOf(drawdown, peak - value)
        }
        return drawdown
    }

    private val HORIZONS = listOf(
        Horizon(3, "3d", 0.08), Horizon(5, "1w", 0.12), Horizon(21, "1m", 0.22),
        Horizon(63, "3m", 0.24), Horizon(126, "6m", 0.18), Horizon(252, "1y", 0.16)
    )
    private const val MIN_SESSIONS = 4
    private const val MAX_SESSIONS = 253
    private const val MAX_TAU_POINTS = 64
    private const val DAY_SECONDS = 86_400L
    private const val EWMA_LAMBDA = 0.94
    private const val MIN_DAILY_VOLATILITY = 0.002
    private const val TIE_EPSILON = 1e-10
}
