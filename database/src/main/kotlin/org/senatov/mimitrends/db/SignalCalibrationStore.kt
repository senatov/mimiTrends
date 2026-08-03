@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ScanResult
import java.sql.Connection

internal class SignalCalibrationStore(private val connection: Connection) {
    fun enrich(result: ScanResult, horizonMinutes: Int = DEFAULT_HORIZON_MINUTES): ScanResult {
        val family = family(result.signalSource)
        val direction = if (result.signalSource.contains('↓')) -1 else 1
        connection.prepareStatement(CALIBRATION_SQL).use { statement ->
            statement.setString(1, family)
            statement.setInt(2, direction)
            statement.setInt(3, horizonMinutes)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return result
                val samples = rows.getInt("samples")
                if (samples < MIN_DISPLAY_SAMPLES) return result.copy(calibrationSamples = samples)
                val wins = rows.getInt("wins")
                val probability = (wins + PRIOR_WINS) / (samples + PRIOR_SAMPLES)
                val calibratedScore = if (samples >= MIN_RANKING_SAMPLES) {
                    result.anomalyScore * (MIN_SCORE_FACTOR + probability * SCORE_PROBABILITY_WEIGHT)
                } else result.anomalyScore
                return result.copy(
                    anomalyScore = calibratedScore,
                    continuationProbability = probability,
                    calibrationSamples = samples,
                    calibrationHorizonMinutes = horizonMinutes
                )
            }
        }
    }

    private fun family(source: String): String = when {
        source.startsWith("V-Reversal") -> "V-Reversal"
        source.startsWith("Momentum") -> "Momentum"
        source.startsWith("Steady rise") || source.startsWith("Trend") -> "Steady rise"
        else -> "Impulse"
    }

    private companion object {
        const val DEFAULT_HORIZON_MINUTES = 10
        const val MIN_DISPLAY_SAMPLES = 5
        const val MIN_RANKING_SAMPLES = 50
        const val PRIOR_WINS = 5.0
        const val PRIOR_SAMPLES = 10.0
        const val MIN_SCORE_FACTOR = 0.75
        const val SCORE_PROBABILITY_WEIGHT = 0.50
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
            SELECT COUNT(*) AS samples,
                COALESCE(SUM(CASE WHEN o.return_percent*e.direction>0 THEN 1 ELSE 0 END), 0) AS wins
            FROM episodes e
            JOIN signal_outcomes o ON o.run_id=e.run_id AND o.symbol=e.symbol
            WHERE e.family=? AND e.direction=? AND o.horizon_minutes=?
        """
    }
}
