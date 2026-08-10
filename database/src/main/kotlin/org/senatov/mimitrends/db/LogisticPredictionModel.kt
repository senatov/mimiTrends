package org.senatov.mimitrends.db

import kotlin.math.exp
import kotlin.math.sqrt

internal data class PredictiveSample(
    val epoch: Long,
    val family: String,
    val direction: Int,
    val rawFeatures: DoubleArray,
    val netReturn: Double
) { val target: Double get() = if (netReturn > 0.0) 1.0 else 0.0 }

internal data class LogisticPredictionModel(
    val means: DoubleArray,
    val scales: DoubleArray,
    val weights: DoubleArray
) {
    fun predict(raw: DoubleArray): Double {
        val normalized = DoubleArray(raw.size) { index ->
            val value = raw[index].takeIf(Double::isFinite) ?: means[index]
            (value - means[index]) / scales[index]
        }
        val score = weights[0] + normalized.indices.sumOf { weights[it + 1] * normalized[it] }
        return sigmoid(score)
    }

    companion object {
        fun fit(samples: List<PredictiveSample>): LogisticPredictionModel {
            require(samples.isNotEmpty())
            val size = samples.first().rawFeatures.size
            val means = DoubleArray(size) { index ->
                samples.map { it.rawFeatures[index] }.filter(Double::isFinite).average().takeIf(Double::isFinite) ?: 0.0
            }
            val scales = DoubleArray(size) { index ->
                val values = samples.map { it.rawFeatures[index] }.filter(Double::isFinite)
                val variance = values.sumOf { (it - means[index]) * (it - means[index]) } / values.size.coerceAtLeast(1)
                sqrt(variance).coerceAtLeast(MIN_SCALE)
            }
            val weights = DoubleArray(size + 1)
            repeat(ITERATIONS) { iteration ->
                val gradient = DoubleArray(weights.size)
                samples.forEach { sample ->
                    val normalized = DoubleArray(size) { index ->
                        ((sample.rawFeatures[index].takeIf(Double::isFinite) ?: means[index]) - means[index]) / scales[index]
                    }
                    val prediction = sigmoid(weights[0] + normalized.indices.sumOf { weights[it + 1] * normalized[it] })
                    val error = prediction - sample.target
                    gradient[0] += error
                    normalized.indices.forEach { gradient[it + 1] += error * normalized[it] }
                }
                val rate = INITIAL_RATE / (1.0 + iteration / 200.0)
                weights[0] -= rate * gradient[0] / samples.size
                for (index in 1..weights.lastIndex) {
                    weights[index] -= rate * (gradient[index] / samples.size + L2 * weights[index])
                }
            }
            return LogisticPredictionModel(means, scales, weights)
        }

        private const val ITERATIONS = 800
        private const val INITIAL_RATE = 0.08
        private const val L2 = 0.02
        private const val MIN_SCALE = 1e-6
    }
}

private fun sigmoid(value: Double): Double = when {
    value >= 0.0 -> 1.0 / (1.0 + exp(-value.coerceAtMost(40.0)))
    else -> exp(value.coerceAtLeast(-40.0)).let { it / (1.0 + it) }
}
