package org.senatov.mimitrends.statistics

import org.apache.commons.statistics.descriptive.Median
import org.apache.commons.statistics.descriptive.Quantile

/** Centralizes finite-sample definitions used by scanner thresholds and persisted analytics. */
object ValidatedStatistics {
    fun median(values: Collection<Double>): Double =
        if (values.isEmpty()) Double.NaN else Median.withDefaults().evaluate(values.toDoubleArray())

    fun quantile(values: Collection<Double>, probability: Double): Double {
        require(values.isNotEmpty()) { "Quantile requires at least one value" }
        require(probability in 0.0..1.0) { "Quantile probability must be between zero and one" }
        return Quantile.withDefaults().evaluate(values.toDoubleArray(), probability)
    }
}
