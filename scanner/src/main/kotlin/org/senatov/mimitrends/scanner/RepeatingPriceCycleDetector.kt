package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MinuteBar
import kotlin.math.abs
import kotlin.math.sqrt

/** Detects a bounded, repeating two- or three-minute price path. */
internal object RepeatingPriceCycleDetector {
    private const val WINDOW_BARS = 24
    private const val MIN_BARS = 12
    private const val MIN_CORRELATION = 0.72
    private const val MAX_PATH_EFFICIENCY = 0.32
    private const val MIN_DIRECTION_CHANGES = 5

    fun strength(bars: List<MinuteBar>): Double {
        val closes = bars.takeLast(WINDOW_BARS).map(MinuteBar::close)
        if (closes.size < MIN_BARS || closes.any { !it.isFinite() || it <= 0.0 }) return Double.NaN
        val changes = closes.zipWithNext { first, last -> last - first }
        val path = changes.sumOf(::abs)
        if (path <= 0.0) return Double.NaN
        val efficiency = abs(closes.last() - closes.first()) / path
        val directionChanges = changes.zipWithNext().count { (first, last) -> first * last < 0.0 }
        if (efficiency > MAX_PATH_EFFICIENCY || directionChanges < MIN_DIRECTION_CHANGES) return Double.NaN
        val correlation = maxOf(autocorrelation(closes, 2), autocorrelation(closes, 3))
        return correlation.takeIf { it >= MIN_CORRELATION } ?: Double.NaN
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
}
