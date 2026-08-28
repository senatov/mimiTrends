package org.senatov.mimitrends.db

import org.senatov.mimitrends.statistics.ValidatedStatistics

internal object RobustStatistics {
    fun median(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else ValidatedStatistics.median(values)
}
