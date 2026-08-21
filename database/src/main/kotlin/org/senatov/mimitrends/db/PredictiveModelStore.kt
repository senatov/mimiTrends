@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ScanResult
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.time.ZoneOffset

internal class PredictiveModelStore(private val connection: Connection) {
    private val active = mutableMapOf<Int, StoredModel>()

    init { loadActiveModels() }

    fun enrich(result: ScanResult, horizonMinutes: Int = DEFAULT_HORIZON): ScanResult {
        val stored = active[horizonMinutes] ?: return result
        val probability = stored.model.predict(features(result, latestUniverseRank(result.symbol)))
        return result.copy(
            continuationProbability = probability,
            calibrationHorizonMinutes = horizonMinutes,
            predictionSource = "LOGISTIC",
            predictionModelVersion = stored.id,
            predictionSamples = stored.trainingSamples,
            empiricalContinuationProbability = result.continuationProbability
        )
    }

    fun trainAll(nowEpoch: Long = Instant.now().epochSecond): List<PredictiveTrainingResult> =
        HORIZONS.map { train(it, nowEpoch) }

    private fun train(horizon: Int, nowEpoch: Long): PredictiveTrainingResult {
        val samples = loadSamples(horizon)
        val latestCutoff = latestTrainingCutoff(horizon)
        if (samples.maxOfOrNull(PredictiveSample::epoch)?.let { it <= latestCutoff } == true) {
            return PredictiveTrainingResult(horizon, "UNCHANGED", samples.size, 0,
                Double.NaN, Double.NaN, "no new completed outcomes")
        }
        val dates = samples.map { day(it.epoch) }.distinct().sorted()
        if (samples.size < MIN_TOTAL_SAMPLES || dates.size < MIN_DAYS) {
            return PredictiveTrainingResult(horizon, "INSUFFICIENT", samples.size, 0,
                Double.NaN, Double.NaN, "need $MIN_TOTAL_SAMPLES samples across $MIN_DAYS days")
        }
        val validationDates = dates.takeLast(VALIDATION_DAYS).toSet()
        val training = samples.filter { day(it.epoch) !in validationDates }
        val validation = samples.filter { day(it.epoch) in validationDates }
        if (training.size < MIN_TRAINING_SAMPLES || validation.size < MIN_VALIDATION_SAMPLES) {
            return PredictiveTrainingResult(horizon, "INSUFFICIENT", training.size, validation.size,
                Double.NaN, Double.NaN, "temporal split is too small")
        }
        if (!hasClassBalance(training, MIN_TRAINING_CLASS_SAMPLES)) {
            return PredictiveTrainingResult(horizon, "INSUFFICIENT", training.size, validation.size,
                Double.NaN, Double.NaN, "training outcomes do not contain enough wins and losses")
        }
        if (!hasClassBalance(validation, MIN_VALIDATION_CLASS_SAMPLES)) {
            return PredictiveTrainingResult(horizon, "INSUFFICIENT", training.size, validation.size,
                Double.NaN, Double.NaN, "validation outcomes do not contain enough wins and losses")
        }
        val model = LogisticPredictionModel.fit(training)
        val modelBrier = brier(validation) { model.predict(it.rawFeatures) }
        val cohorts = training.groupBy { it.family to it.direction }
        val baselineBrier = brier(validation) { sample ->
            val cohort = cohorts[sample.family to sample.direction].orEmpty()
            (cohort.count { it.target > 0.5 } + 1.0) / (cohort.size + 2.0)
        }
        val ranked = validation.sortedByDescending { model.predict(it.rawFeatures) }
        val topNet = ranked.take((ranked.size / 4).coerceAtLeast(1)).map(PredictiveSample::netReturn).average()
        val averageNet = validation.map(PredictiveSample::netReturn).average()
        val accepted = modelBrier + MIN_BRIER_IMPROVEMENT <= baselineBrier && topNet >= averageNet
        val reason = if (accepted) null else "candidate did not improve temporal baseline"
        val id = persist(horizon, nowEpoch, training, validation, model, modelBrier, baselineBrier,
            averageNet, topNet, accepted, reason)
        if (accepted) active[horizon] = StoredModel(id, training.size, model)
        return PredictiveTrainingResult(horizon, if (accepted) "ACTIVE" else "REJECTED",
            training.size, validation.size, modelBrier, baselineBrier, reason)
    }

    private fun loadSamples(horizon: Int): List<PredictiveSample> = connection.prepareStatement(LOAD_SAMPLES_SQL).use { statement ->
        statement.setInt(1, horizon)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) {
                val direction = rows.getInt("direction")
                val rawReturn = rows.getDouble("return_percent")
                add(PredictiveSample(rows.getLong("observed_epoch"), rows.getString("family"), direction,
                    features(
                        rows.nullableDouble("score"), rows.nullableDouble("jump_z"), rows.nullableDouble("range_z"),
                        rows.nullableDouble("volume_z"), rows.nullableDouble("rvol"), rows.nullableDouble("return_10m"),
                        direction, rows.getString("family"), rows.getInt("universe_rank").takeIf { !rows.wasNull() }
                    ), direction * rawReturn - FRICTION_PERCENT))
            }
        } }
    }

    private fun persist(
        horizon: Int, nowEpoch: Long, training: List<PredictiveSample>, validation: List<PredictiveSample>,
        model: LogisticPredictionModel, modelBrier: Double, baselineBrier: Double,
        averageNet: Double, topNet: Double, accepted: Boolean, reason: String?
    ): Long {
        if (accepted) connection.prepareStatement(
            "UPDATE predictive_models SET status='SUPERSEDED' WHERE horizon_minutes=? AND status='ACTIVE'"
        ).use { it.setInt(1, horizon); it.executeUpdate() }
        return connection.prepareStatement(INSERT_MODEL_SQL, Statement.RETURN_GENERATED_KEYS).use { statement ->
            statement.setInt(1, horizon); statement.setInt(2, FEATURE_VERSION); statement.setLong(3, nowEpoch)
            statement.setLong(4, maxOf(training.maxOf(PredictiveSample::epoch), validation.maxOf(PredictiveSample::epoch)))
            statement.setInt(5, training.size)
            statement.setInt(6, validation.size); statement.setInt(7, validation.map { day(it.epoch) }.distinct().size)
            statement.setDouble(8, modelBrier); statement.setDouble(9, baselineBrier)
            statement.setDouble(10, averageNet); statement.setDouble(11, topNet)
            statement.setString(12, encode(model.means)); statement.setString(13, encode(model.scales))
            statement.setString(14, encode(model.weights)); statement.setString(15, if (accepted) "ACTIVE" else "REJECTED")
            statement.setString(16, reason); statement.executeUpdate()
            statement.generatedKeys.use { keys -> check(keys.next()); keys.getLong(1) }
        }
    }

    private fun loadActiveModels() {
        connection.createStatement().executeQuery("""SELECT id, horizon_minutes, training_samples, means, scales, weights
            FROM predictive_models WHERE status='ACTIVE' AND feature_version=$FEATURE_VERSION""").use { rows ->
            while (rows.next()) active[rows.getInt(2)] = StoredModel(rows.getLong(1), rows.getInt(3),
                LogisticPredictionModel(decode(rows.getString(4)), decode(rows.getString(5)), decode(rows.getString(6))))
        }
    }

    private fun latestTrainingCutoff(horizon: Int): Long = connection.prepareStatement(
        "SELECT COALESCE(MAX(training_cutoff), 0) FROM predictive_models WHERE horizon_minutes=?"
    ).use { statement ->
        statement.setInt(1, horizon)
        statement.executeQuery().use { it.next(); it.getLong(1) }
    }

    private fun features(result: ScanResult, universeRank: Int?): DoubleArray = features(result.anomalyScore, result.priceAnomaly,
        result.rangeAnomaly, result.volumeAnomaly, result.relativeVolume, result.windowChangePercent,
        if (result.signalSource.contains('↓')) -1 else 1, family(result.signalSource), universeRank)

    private fun features(score: Double, jump: Double, range: Double, volume: Double, rvol: Double,
        return10m: Double, direction: Int, family: String, universeRank: Int?): DoubleArray {
        val families = FAMILIES.map { if (family == it) 1.0 else 0.0 }
        val universeStrength = universeRank?.let { (51 - it.coerceIn(1, 50)) / 50.0 } ?: 0.5
        return doubleArrayOf(score, jump, range, volume, rvol, return10m, direction.toDouble(),
            universeStrength, *families.toDoubleArray())
    }

    private fun latestUniverseRank(symbol: String): Int? = connection.prepareStatement(
        "SELECT rank FROM universe_membership WHERE symbol=? ORDER BY selection_date DESC LIMIT 1"
    ).use { statement ->
        statement.setString(1, symbol.uppercase())
        statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else null }
    }

    private fun family(source: String): String = when {
        source.startsWith("V-Reversal") -> "V-Reversal"
        source.startsWith("Momentum") -> "Momentum"
        source.startsWith("Steady rise") || source.startsWith("Trend") -> "Steady rise"
        source.startsWith("Early recovery") -> "Early recovery"
        else -> "Impulse"
    }

    // Glenn W. Brier, "Verification of Forecasts Expressed in Terms of Probability" (1950).
    // https://doi.org/10.1175/1520-0493(1950)078%3C0001:VOFEIT%3E2.0.CO;2
    private fun brier(samples: List<PredictiveSample>, probability: (PredictiveSample) -> Double): Double =
        samples.map { val error = probability(it) - it.target; error * error }.average()
    private fun hasClassBalance(samples: List<PredictiveSample>, minimum: Int): Boolean {
        val wins = samples.count { it.target > 0.5 }
        return wins >= minimum && samples.size - wins >= minimum
    }
    private fun day(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate()
    private fun encode(values: DoubleArray) = values.joinToString(",")
    private fun decode(value: String) = value.split(',').map(String::toDouble).toDoubleArray()
    private fun java.sql.ResultSet.nullableDouble(name: String) = getDouble(name).let { if (wasNull()) Double.NaN else it }
    private data class StoredModel(val id: Long, val trainingSamples: Int, val model: LogisticPredictionModel)

    private companion object {
        const val DEFAULT_HORIZON = 10
        const val FEATURE_VERSION = 3
        const val FRICTION_PERCENT = 0.20
        const val MIN_TOTAL_SAMPLES = 300
        const val MIN_TRAINING_SAMPLES = 200
        const val MIN_VALIDATION_SAMPLES = 50
        const val MIN_TRAINING_CLASS_SAMPLES = 20
        const val MIN_VALIDATION_CLASS_SAMPLES = 10
        const val MIN_DAYS = 7
        const val VALIDATION_DAYS = 2
        const val MIN_BRIER_IMPROVEMENT = 0.001
        val HORIZONS = listOf(5, 10, 30)
        val FAMILIES = listOf("Control", "Impulse", "Momentum", "V-Reversal", "Steady rise", "Early recovery")
        const val LOAD_SAMPLES_SQL = """SELECT s.observed_epoch, s.family, s.direction, s.score, s.jump_z,
            s.range_z, s.volume_z, s.rvol, s.return_10m, o.return_percent, u.rank AS universe_rank
            FROM research_samples s JOIN research_outcomes o ON o.sample_id=s.id
            LEFT JOIN universe_membership u ON u.symbol=s.symbol
                AND u.selection_date=date(s.observed_epoch, 'unixepoch')
            WHERE o.horizon_minutes=? ORDER BY s.observed_epoch"""
        const val INSERT_MODEL_SQL = """INSERT INTO predictive_models(horizon_minutes, feature_version,
            trained_at, training_cutoff, training_samples, validation_samples, validation_days,
            model_brier, baseline_brier, average_net_return, top_quartile_net_return,
            means, scales, weights, status, rejection_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
    }
}
