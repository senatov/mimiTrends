@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ScanResult
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.sqrt
import org.senatov.mimitrends.statistics.ValidatedStatistics

internal class SignalCalibrationStore(private val connection: Connection) {
    fun enrich(result: ScanResult, horizonMinutes: Int = DEFAULT_HORIZON_MINUTES): ScanResult {
        val direction = if (result.signalSource.contains('↓')) -1 else 1
        val signalFamily = family(result.signalSource)
        val normalized = result.copy(rankingPercentile = historicalPercentile(result, signalFamily, direction))
        val cutoffEpoch = result.signalEpochMillis / 1_000L
        val cohort = CalibrationCohort(
            relaxed = result.signalSource.contains("relaxed"),
            european = result.symbol.contains('.'),
            realtime = isRealtime(result.dataStatus)
        )
        val exactSamples = loadSamples(signalFamily, direction, horizonMinutes, cutoffEpoch, cohort)
        val samples = if (hasRepresentativeSample(exactSamples)) exactSamples else
            loadSamples(signalFamily, direction, horizonMinutes, cutoffEpoch, null)
        val netReturns = samples.map { direction * it.returnPercent - ASSUMED_FRICTION_PERCENT }
        val wins = netReturns.count { it > 0.0 }
        val probability = (wins + PRIOR_WINS) / (samples.size + PRIOR_SAMPLES)
        if (!hasRepresentativeSample(samples)) return normalized.copy(
            continuationProbability = probability.takeIf { samples.isNotEmpty() } ?: Double.NaN,
            calibrationSamples = samples.size,
            calibrationHorizonMinutes = horizonMinutes
        )
        val (lowerBound, upperBound) = wilsonInterval(wins.toDouble(), samples.size.toDouble())
        val favorable = samples.mapNotNull { it.favorableExcursion(direction) }
        val adverse = samples.mapNotNull { it.adverseExcursion(direction) }
        return normalized.copy(
            continuationProbability = probability,
            calibrationSamples = samples.size,
            calibrationHorizonMinutes = horizonMinutes,
            continuationLowerBound = lowerBound,
            continuationUpperBound = upperBound,
            medianNetReturnPercent = ValidatedStatistics.quantile(netReturns, 0.50),
            lowerQuartileNetReturnPercent = ValidatedStatistics.quantile(netReturns, 0.25),
            upperQuartileNetReturnPercent = ValidatedStatistics.quantile(netReturns, 0.75),
            medianFavorableExcursionPercent = medianOrNaN(favorable),
            medianAdverseExcursionPercent = medianOrNaN(adverse)
        )
    }

    private fun loadSamples(
        family: String,
        direction: Int,
        horizonMinutes: Int,
        cutoffEpoch: Long,
        cohort: CalibrationCohort?
    ): List<OutcomeSample> =
        connection.prepareStatement(CALIBRATION_SQL).use { statement ->
            statement.setString(1, family)
            statement.setInt(2, direction)
            statement.setInt(3, horizonMinutes)
            statement.setLong(4, cutoffEpoch)
            statement.setInt(5, if (cohort == null) 0 else 1)
            statement.setInt(6, if (cohort?.relaxed == true) 1 else 0)
            statement.setInt(7, if (cohort?.european == true) 1 else 0)
            statement.setInt(8, if (cohort?.realtime == true) 1 else 0)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(OutcomeSample(
                    symbol = rows.getString("symbol"),
                    signalEpoch = rows.getLong("signal_epoch"),
                    returnPercent = rows.getDouble("return_percent"),
                    maximumReturnPercent = rows.getDouble("maximum_return_percent").let { if (rows.wasNull()) null else it },
                    minimumReturnPercent = rows.getDouble("minimum_return_percent").let { if (rows.wasNull()) null else it }
                ))
            } }
        }

    private fun historicalPercentile(result: ScanResult, family: String, direction: Int): Double {
        val scores = connection.prepareStatement(SCORE_HISTORY_SQL).use { statement ->
            statement.setString(1, family)
            statement.setInt(2, direction)
            statement.setLong(3, result.signalEpochMillis / 1_000L)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(rows.getDouble("score"))
            } }
        }
        if (scores.size < MIN_SCORE_SAMPLES) return Double.NaN
        val below = scores.count { it < result.anomalyScore }
        val equal = scores.count { it == result.anomalyScore }
        return SCORE_SCALE * (below + 0.5 * equal) / scores.size
    }

    private fun hasRepresentativeSample(samples: List<OutcomeSample>): Boolean =
        samples.size >= MIN_DISPLAY_SAMPLES &&
            samples.map(OutcomeSample::symbol).distinct().size >= MIN_DISTINCT_SYMBOLS &&
            samples.map { Instant.ofEpochSecond(it.signalEpoch).atZone(ZoneOffset.UTC).toLocalDate() }
                .distinct().size >= MIN_DISTINCT_DAYS

    private fun family(source: String): String = when {
        source.startsWith("V-Reversal") -> "V-Reversal"
        source.startsWith("Momentum") -> "Momentum"
        source.startsWith("Steady rise") || source.startsWith("Trend") -> "Steady rise"
        source.startsWith("Early recovery") || source.startsWith("Recovery rise") ||
            source.startsWith("Recovery breakout") -> "Recovery"
        source.startsWith("Oversold decline") -> "Oversold"
        else -> "Impulse"
    }

    private fun isRealtime(source: String): Boolean =
        source.contains("LIVE", ignoreCase = true) || source.contains("RT", ignoreCase = true)

    private fun medianOrNaN(values: List<Double>): Double =
        ValidatedStatistics.median(values)

    // Edwin B. Wilson, "Probable Inference, the Law of Succession, and Statistical Inference" (1927).
    // https://doi.org/10.1080/01621459.1927.10502953
    private fun wilsonInterval(wins: Double, samples: Double): Pair<Double, Double> {
        val observed = wins / samples
        val denominator = 1.0 + Z95 * Z95 / samples
        val center = (observed + Z95 * Z95 / (2.0 * samples)) / denominator
        val margin = Z95 * sqrt((observed * (1.0 - observed) + Z95 * Z95 / (4.0 * samples)) / samples) / denominator
        return (center - margin).coerceAtLeast(0.0) to (center + margin).coerceAtMost(1.0)
    }

    private data class OutcomeSample(
        val symbol: String,
        val signalEpoch: Long,
        val returnPercent: Double,
        val maximumReturnPercent: Double?,
        val minimumReturnPercent: Double?
    ) {
        fun favorableExcursion(direction: Int): Double? =
            (if (direction > 0) maximumReturnPercent else minimumReturnPercent?.let { -it })?.coerceAtLeast(0.0)

        fun adverseExcursion(direction: Int): Double? =
            (if (direction > 0) minimumReturnPercent else maximumReturnPercent?.let { -it })?.coerceAtMost(0.0)
    }

    private data class CalibrationCohort(
        val relaxed: Boolean,
        val european: Boolean,
        val realtime: Boolean
    )

    private companion object {
        const val DEFAULT_HORIZON_MINUTES = 10
        const val MIN_DISPLAY_SAMPLES = 12
        const val MIN_SCORE_SAMPLES = 30
        const val MIN_DISTINCT_SYMBOLS = 5
        const val MIN_DISTINCT_DAYS = 3
        const val ASSUMED_FRICTION_PERCENT = 0.20
        const val PRIOR_WINS = 1.0
        const val PRIOR_SAMPLES = 2.0
        const val Z95 = 1.959963984540054
        const val SCORE_SCALE = 10.0
        const val CALIBRATION_SQL = """
            WITH classified AS (
                SELECT c.run_id, c.symbol, c.signal_epoch,
                    CASE
                        WHEN c.signal LIKE 'V-Reversal%' THEN 'V-Reversal'
                        WHEN c.signal LIKE 'Momentum%' THEN 'Momentum'
                        WHEN c.signal LIKE 'Steady rise%' OR c.signal LIKE 'Trend%' THEN 'Steady rise'
                        WHEN c.signal LIKE 'Early recovery%' OR c.signal LIKE 'Recovery rise%'
                            OR c.signal LIKE 'Recovery breakout%' THEN 'Recovery'
                        WHEN c.signal LIKE 'Oversold decline%' THEN 'Oversold'
                        ELSE 'Impulse'
                    END AS family,
                    CASE WHEN c.signal LIKE '%↓%' THEN -1 ELSE 1 END AS direction,
                    CASE WHEN c.signal LIKE '%relaxed%' THEN 1 ELSE 0 END AS relaxed,
                    CASE WHEN instr(c.symbol, '.') > 0 THEN 1 ELSE 0 END AS european,
                    CASE WHEN upper(c.source) LIKE '%LIVE%' OR upper(c.source) LIKE '%RT%' THEN 1 ELSE 0 END AS realtime,
                    LAG(c.signal_epoch) OVER (
                        PARTITION BY c.symbol,
                            CASE
                                WHEN c.signal LIKE 'V-Reversal%' THEN 'V-Reversal'
                                WHEN c.signal LIKE 'Momentum%' THEN 'Momentum'
                                WHEN c.signal LIKE 'Steady rise%' OR c.signal LIKE 'Trend%' THEN 'Steady rise'
                                WHEN c.signal LIKE 'Early recovery%' OR c.signal LIKE 'Recovery rise%'
                                    OR c.signal LIKE 'Recovery breakout%' THEN 'Recovery'
                                WHEN c.signal LIKE 'Oversold decline%' THEN 'Oversold'
                                ELSE 'Impulse'
                            END,
                            CASE WHEN c.signal LIKE '%↓%' THEN -1 ELSE 1 END
                        ORDER BY c.signal_epoch, c.run_id
                    ) AS previous_epoch
                FROM scan_candidates c
                WHERE c.accepted=1 AND c.published=1 AND c.signal_epoch IS NOT NULL
                    AND upper(c.source) NOT IN ('BOERSE_DE', 'BNP_PARIBAS', 'TRADERFOX')
            ), episodes AS (
                SELECT * FROM classified
                WHERE previous_epoch IS NULL OR signal_epoch-previous_epoch>=900
            )
            SELECT e.symbol, e.signal_epoch, o.return_percent,
                o.maximum_return_percent, o.minimum_return_percent
            FROM episodes e
            JOIN signal_outcomes o ON o.run_id=e.run_id AND o.symbol=e.symbol
            WHERE e.family=? AND e.direction=? AND o.horizon_minutes=? AND e.signal_epoch<?
                AND (?=0 OR (e.relaxed=? AND e.european=? AND e.realtime=?))
        """
        const val SCORE_HISTORY_SQL = """
            SELECT score FROM scan_candidates
            WHERE accepted=1 AND score IS NOT NULL
                AND CASE
                    WHEN signal LIKE 'V-Reversal%' THEN 'V-Reversal'
                    WHEN signal LIKE 'Momentum%' THEN 'Momentum'
                    WHEN signal LIKE 'Steady rise%' OR signal LIKE 'Trend%' THEN 'Steady rise'
                    WHEN signal LIKE 'Early recovery%' OR signal LIKE 'Recovery rise%'
                        OR signal LIKE 'Recovery breakout%' THEN 'Recovery'
                    WHEN signal LIKE 'Oversold decline%' THEN 'Oversold'
                    ELSE 'Impulse'
                END=?
                AND CASE WHEN signal LIKE '%↓%' THEN -1 ELSE 1 END=?
                AND upper(source) NOT IN ('BOERSE_DE', 'BNP_PARIBAS', 'TRADERFOX')
                AND signal_epoch<?
        """
    }
}
