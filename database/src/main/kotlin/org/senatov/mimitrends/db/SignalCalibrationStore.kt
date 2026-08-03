@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ScanResult
import java.sql.Connection
import kotlin.math.sqrt

internal class SignalCalibrationStore(private val connection: Connection) {
    fun enrich(result: ScanResult, horizonMinutes: Int = DEFAULT_HORIZON_MINUTES): ScanResult {
        val direction = if (result.signalSource.contains('↓')) -1 else 1
        val samples = loadSamples(family(result.signalSource), direction, horizonMinutes)
        if (samples.size < MIN_DISPLAY_SAMPLES) return result.copy(calibrationSamples = samples.size)

        val netReturns = samples.map { direction * it.returnPercent - ASSUMED_FRICTION_PERCENT }
        val wins = netReturns.count { it > 0.0 }
        val probability = (wins + PRIOR_WINS) / (samples.size + PRIOR_SAMPLES)
        val (lowerBound, upperBound) = wilsonInterval(wins, samples.size)
        val favorable = samples.mapNotNull { it.favorableExcursion(direction) }
        val adverse = samples.mapNotNull { it.adverseExcursion(direction) }
        return result.copy(
            continuationProbability = probability,
            calibrationSamples = samples.size,
            calibrationHorizonMinutes = horizonMinutes,
            continuationLowerBound = lowerBound,
            continuationUpperBound = upperBound,
            medianNetReturnPercent = percentile(netReturns, 0.50),
            lowerQuartileNetReturnPercent = percentile(netReturns, 0.25),
            upperQuartileNetReturnPercent = percentile(netReturns, 0.75),
            medianFavorableExcursionPercent = medianOrNaN(favorable),
            medianAdverseExcursionPercent = medianOrNaN(adverse)
        )
    }

    private fun loadSamples(family: String, direction: Int, horizonMinutes: Int): List<OutcomeSample> =
        connection.prepareStatement(CALIBRATION_SQL).use { statement ->
            statement.setString(1, family)
            statement.setInt(2, direction)
            statement.setInt(3, horizonMinutes)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(OutcomeSample(
                    returnPercent = rows.getDouble("return_percent"),
                    maximumReturnPercent = rows.getDouble("maximum_return_percent").let { if (rows.wasNull()) null else it },
                    minimumReturnPercent = rows.getDouble("minimum_return_percent").let { if (rows.wasNull()) null else it }
                ))
            } }
        }

    private fun family(source: String): String = when {
        source.startsWith("V-Reversal") -> "V-Reversal"
        source.startsWith("Momentum") -> "Momentum"
        source.startsWith("Steady rise") || source.startsWith("Trend") -> "Steady rise"
        else -> "Impulse"
    }

    private fun medianOrNaN(values: List<Double>): Double =
        if (values.isEmpty()) Double.NaN else percentile(values, 0.50)

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        val position = (sorted.lastIndex * fraction).coerceIn(0.0, sorted.lastIndex.toDouble())
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun wilsonInterval(wins: Int, samples: Int): Pair<Double, Double> {
        val observed = wins.toDouble() / samples
        val denominator = 1.0 + Z95 * Z95 / samples
        val center = (observed + Z95 * Z95 / (2.0 * samples)) / denominator
        val margin = Z95 * sqrt((observed * (1.0 - observed) + Z95 * Z95 / (4.0 * samples)) / samples) / denominator
        return (center - margin).coerceAtLeast(0.0) to (center + margin).coerceAtMost(1.0)
    }

    private data class OutcomeSample(
        val returnPercent: Double,
        val maximumReturnPercent: Double?,
        val minimumReturnPercent: Double?
    ) {
        fun favorableExcursion(direction: Int): Double? =
            (if (direction > 0) maximumReturnPercent else minimumReturnPercent?.let { -it })?.coerceAtLeast(0.0)

        fun adverseExcursion(direction: Int): Double? =
            (if (direction > 0) minimumReturnPercent else maximumReturnPercent?.let { -it })?.coerceAtMost(0.0)
    }

    private companion object {
        const val DEFAULT_HORIZON_MINUTES = 10
        const val MIN_DISPLAY_SAMPLES = 5
        const val ASSUMED_FRICTION_PERCENT = 0.20
        const val PRIOR_WINS = 1.0
        const val PRIOR_SAMPLES = 2.0
        const val Z95 = 1.959963984540054
        const val CALIBRATION_SQL = """
            WITH classified AS (
                SELECT c.run_id, c.symbol, c.signal_epoch,
                    CASE
                        WHEN c.signal LIKE 'V-Reversal%' THEN 'V-Reversal'
                        WHEN c.signal LIKE 'Momentum%' THEN 'Momentum'
                        WHEN c.signal LIKE 'Steady rise%' OR c.signal LIKE 'Trend%' THEN 'Steady rise'
                        ELSE 'Impulse'
                    END AS family,
                    CASE WHEN c.signal LIKE '%↓%' THEN -1 ELSE 1 END AS direction,
                    LAG(c.signal_epoch) OVER (
                        PARTITION BY c.symbol,
                            CASE
                                WHEN c.signal LIKE 'V-Reversal%' THEN 'V-Reversal'
                                WHEN c.signal LIKE 'Momentum%' THEN 'Momentum'
                                WHEN c.signal LIKE 'Steady rise%' OR c.signal LIKE 'Trend%' THEN 'Steady rise'
                                ELSE 'Impulse'
                            END,
                            CASE WHEN c.signal LIKE '%↓%' THEN -1 ELSE 1 END
                        ORDER BY c.signal_epoch, c.run_id
                    ) AS previous_epoch
                FROM scan_candidates c
                WHERE c.accepted=1 AND c.published=1 AND c.signal_epoch IS NOT NULL
            ), episodes AS (
                SELECT * FROM classified
                WHERE previous_epoch IS NULL OR signal_epoch-previous_epoch>=900
            )
            SELECT o.return_percent, o.maximum_return_percent, o.minimum_return_percent
            FROM episodes e
            JOIN signal_outcomes o ON o.run_id=e.run_id AND o.symbol=e.symbol
            WHERE e.family=? AND e.direction=? AND o.horizon_minutes=?
        """
    }
}
