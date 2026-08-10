@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset

internal class WalkForwardResearchEvaluator(private val connection: Connection) {
    fun evaluate(horizonMinutes: Int, frictionPercent: Double): WalkForwardResearchReport {
        require(horizonMinutes in setOf(5, 10, 30)) { "Unsupported research horizon: $horizonMinutes" }
        require(frictionPercent.isFinite() && frictionPercent >= 0.0) { "Friction must be finite and non-negative" }
        val rows = loadRows(horizonMinutes).sortedBy(Row::epoch)
        val evaluated = rows.mapNotNull { row ->
            val day = day(row.epoch)
            val training = rows.filter {
                it.family == row.family && it.direction == row.direction && day(it.epoch) < day
            }
            if (training.size < MIN_TRAINING_SAMPLES) return@mapNotNull null
            val trainingWins = training.count { netReturn(it, frictionPercent) > 0.0 }
            Evaluated(row, (trainingWins + PRIOR_WINS) / (training.size + PRIOR_SAMPLES),
                netReturn(row, frictionPercent))
        }
        val metrics = evaluated.groupBy { it.row.family to it.row.direction }.map { (key, values) ->
            val wins = values.count { it.netReturn > 0.0 }
            WalkForwardMetric(
                family = key.first,
                direction = key.second,
                samples = values.size,
                distinctDays = values.map { day(it.row.epoch) }.distinct().size,
                predictedWinRate = values.map(Evaluated::probability).average(),
                actualWinRate = wins.toDouble() / values.size,
                brierScore = values.map { sample ->
                    val actual = if (sample.netReturn > 0.0) 1.0 else 0.0
                    (sample.probability - actual) * (sample.probability - actual)
                }.average(),
                averageNetReturnPercent = values.map(Evaluated::netReturn).average()
            )
        }.sortedWith(compareByDescending<WalkForwardMetric> { it.samples }.thenBy { it.family })
        return WalkForwardResearchReport(horizonMinutes, frictionPercent, rows.size, evaluated.size, metrics)
    }

    private fun loadRows(horizon: Int): List<Row> = connection.prepareStatement("""SELECT
        s.family, s.direction, s.observed_epoch, o.return_percent
        FROM research_samples s JOIN research_outcomes o ON o.sample_id=s.id
        WHERE o.horizon_minutes=? ORDER BY s.observed_epoch""").use { statement ->
        statement.setInt(1, horizon)
        statement.executeQuery().use { result -> buildList {
            while (result.next()) add(Row(result.getString(1), result.getInt(2), result.getLong(3), result.getDouble(4)))
        } }
    }

    private fun netReturn(row: Row, friction: Double) = row.direction * row.returnPercent - friction

    private fun day(epoch: Long) = Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate()

    private data class Row(val family: String, val direction: Int, val epoch: Long, val returnPercent: Double)
    private data class Evaluated(val row: Row, val probability: Double, val netReturn: Double)

    private companion object {
        const val MIN_TRAINING_SAMPLES = 20
        const val PRIOR_WINS = 1.0
        const val PRIOR_SAMPLES = 2.0
    }
}
