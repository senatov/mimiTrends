package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MinuteBar
import kotlin.math.abs
import kotlin.math.sqrt

/** Detects a bounded, repeating two- or three-minute price path. */
internal object RepeatingPriceCycleDetector {
    private const val WINDOW_BARS = 24
    private const val MIN_BARS = 9
    private const val MIN_CORRELATION = 0.60
    private const val MAX_PATH_EFFICIENCY = 0.40
    private const val MIN_DIRECTION_CHANGES = 4

    fun strength(bars: List<MinuteBar>): Double {
        val closes = bars.takeLast(WINDOW_BARS).map(MinuteBar::close)
        if (closes.size < MIN_BARS || closes.any { !it.isFinite() || it <= 0.0 }) return Double.NaN
        val changes = closes.zipWithNext { first, last -> last - first }
        val path = changes.sumOf(::abs)
        if (path <= 0.0) return Double.NaN
        val efficiency = abs(closes.last() - closes.first()) / path
        val minimumChange = closes.average() * MIN_DIRECTIONAL_CHANGE_PERCENT / 100.0
        val directions = changes.filter { abs(it) >= minimumChange }.map { if (it > 0.0) 1 else -1 }
        val directionChanges = directions.zipWithNext().count { (first, last) -> first != last }
        if (directionChanges < MIN_DIRECTION_CHANGES) return Double.NaN
        val boundedCorrelation = if (efficiency <= MAX_PATH_EFFICIENCY) bestCorrelation(closes) else Double.NaN
        val residuals = detrend(closes)
        val residualRange = requireNotNull(residuals.maxOrNull()) - requireNotNull(residuals.minOrNull())
        val shiftingCorrelation = bestCorrelation(residuals).takeIf {
            residualRange >= changes.map(::abs).average() * MIN_RESIDUAL_RANGE_MULTIPLIER
        } ?: Double.NaN
        val correlation = maxOf(boundedCorrelation, shiftingCorrelation)
        return correlation.takeIf { it >= MIN_CORRELATION } ?: Double.NaN
    }

    private fun bestCorrelation(values: List<Double>): Double =
        maxOf(autocorrelation(values, 2), autocorrelation(values, 3))

    private fun detrend(values: List<Double>): List<Double> {
        val meanX = (values.size - 1) / 2.0
        val meanY = values.average()
        val denominator = values.indices.sumOf { index -> (index - meanX) * (index - meanX) }
        val slope = if (denominator > 0.0) values.indices.sumOf { index ->
            (index - meanX) * (values[index] - meanY)
        } / denominator else 0.0
        return values.mapIndexed { index, value -> value - (meanY + slope * (index - meanX)) }
    }

    private fun autocorrelation(values: List<Double>, lag: Int): Double {
        val left = values.dropLast(lag)
        val right = values.drop(lag)
        val leftMean = left.average()
        val rightMean = right.average()
        var covariance = 0.0
        var leftVariance = 0.0
        var rightVariance = 0.0
        for (index in left.indices) {
            val a = left[index] - leftMean
            val b = right[index] - rightMean
            covariance += a * b
            leftVariance += a * a
            rightVariance += b * b
        }
        val denominator = sqrt(leftVariance * rightVariance)
        return if (denominator > 0.0) covariance / denominator else Double.NaN
    }

    private const val MIN_DIRECTIONAL_CHANGE_PERCENT = 0.005
    private const val MIN_RESIDUAL_RANGE_MULTIPLIER = 1.5
}
