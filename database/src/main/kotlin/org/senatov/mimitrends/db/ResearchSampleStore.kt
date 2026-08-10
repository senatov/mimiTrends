@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.ScanResult
import java.sql.Connection
import java.sql.Types

internal class ResearchSampleStore(private val connection: Connection) {
    fun record(runId: Long, symbol: String, result: ScanResult?, features: ResearchFeatures, source: String) {
        if (!features.entryPrice.isFinite() || features.entryPrice <= 0.0) return
        val family = result?.let { family(it.signalSource) } ?: CONTROL_FAMILY
        val direction = result?.let { if (it.signalSource.contains('↓')) -1 else 1 } ?: 1
        if (hasRecentEpisode(symbol, family, direction, features.observedEpochSeconds)) return
        connection.prepareStatement(INSERT_SQL).use { statement ->
            var index = 1
            statement.setLong(index++, runId)
            statement.setString(index++, symbol.uppercase())
            statement.setLong(index++, features.observedEpochSeconds)
            statement.setDouble(index++, features.entryPrice)
            statement.setString(index++, family)
            statement.setInt(index++, direction)
            statement.setInt(index++, if (result == null) 0 else 1)
            statement.setString(index++, source)
            metric(statement, index++, result?.anomalyScore)
            metric(statement, index++, result?.priceAnomaly)
            metric(statement, index++, result?.rangeAnomaly)
            metric(statement, index++, result?.volumeAnomaly)
            metric(statement, index++, result?.relativeVolume)
            listOf(
                features.return1mPercent, features.return3mPercent, features.return5mPercent,
                features.return10mPercent, features.return30mPercent, features.return60mPercent,
                features.range10mPercent, features.realizedVolatility30m, features.vwapDistancePercent,
                features.sessionHighDistancePercent, features.sessionLowDistancePercent,
                features.volumeRatio10m, features.trendEfficiency10m
            ).forEach { metric(statement, index++, it) }
            statement.executeUpdate()
        }
    }

    fun recordHistorical(
        symbol: String,
        result: ScanResult?,
        features: ResearchFeatures,
        outcomes: Collection<ResearchBackfillOutcome>
    ) {
        record(0L, symbol, result, features, HISTORICAL_SOURCE)
        val family = result?.let { family(it.signalSource) } ?: CONTROL_FAMILY
        val direction = result?.let { if (it.signalSource.contains('↓')) -1 else 1 } ?: 1
        val sampleId = sampleId(symbol, features.observedEpochSeconds, family, direction) ?: return
        connection.prepareStatement(HISTORICAL_OUTCOME_SQL).use { statement ->
            outcomes.forEach { outcome ->
                statement.setLong(1, sampleId)
                statement.setInt(2, outcome.horizonMinutes)
                statement.setDouble(3, outcome.observedPrice)
                statement.setDouble(4, outcome.returnPercent)
                statement.setDouble(5, outcome.elapsedMinutes)
                statement.setDouble(6, outcome.maximumReturnPercent)
                statement.setDouble(7, outcome.minimumReturnPercent)
                statement.setLong(8, outcome.observedEpochSeconds)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    fun markPublished(runId: Long, symbols: Collection<String>) {
        if (symbols.isEmpty()) return
        connection.prepareStatement("UPDATE research_samples SET published=1 WHERE run_id=? AND symbol=?").use { statement ->
            symbols.forEach { symbol ->
                statement.setLong(1, runId)
                statement.setString(2, symbol.uppercase())
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    fun recordOutcomes(symbol: String, close: Double, high: Double, low: Double, observedEpoch: Long) {
        if (listOf(close, high, low).any { !it.isFinite() || it <= 0.0 }) return
        updateExcursions(symbol.uppercase(), high, low, observedEpoch)
        HORIZONS.forEach { insertOutcome(symbol.uppercase(), close, observedEpoch, it) }
    }

    private fun hasRecentEpisode(symbol: String, family: String, direction: Int, epoch: Long): Boolean =
        connection.prepareStatement("""SELECT 1 FROM research_samples WHERE symbol=? AND family=? AND direction=?
            AND observed_epoch>? AND observed_epoch<=? ORDER BY observed_epoch DESC LIMIT 1""").use { statement ->
            statement.setString(1, symbol.uppercase())
            statement.setString(2, family)
            statement.setInt(3, direction)
            statement.setLong(4, epoch - EPISODE_SECONDS)
            statement.setLong(5, epoch)
            statement.executeQuery().use { it.next() }
        }

    private fun sampleId(symbol: String, epoch: Long, family: String, direction: Int): Long? =
        connection.prepareStatement("""SELECT id FROM research_samples
            WHERE symbol=? AND observed_epoch=? AND family=? AND direction=?""").use { statement ->
            statement.setString(1, symbol.uppercase())
            statement.setLong(2, epoch)
            statement.setString(3, family)
            statement.setInt(4, direction)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else null }
        }

    private fun updateExcursions(symbol: String, high: Double, low: Double, epoch: Long) {
        connection.prepareStatement("""INSERT INTO research_excursions
            (sample_id, maximum_return_percent, minimum_return_percent, last_observed_at)
            SELECT id, (? / entry_price - 1.0) * 100.0, (? / entry_price - 1.0) * 100.0, ?
            FROM research_samples WHERE symbol=? AND observed_epoch<=? AND observed_epoch>=?
            ON CONFLICT(sample_id) DO UPDATE SET
            maximum_return_percent=MAX(research_excursions.maximum_return_percent, excluded.maximum_return_percent),
            minimum_return_percent=MIN(research_excursions.minimum_return_percent, excluded.minimum_return_percent),
            last_observed_at=excluded.last_observed_at""").use { statement ->
            statement.setDouble(1, high)
            statement.setDouble(2, low)
            statement.setLong(3, epoch)
            statement.setString(4, symbol)
            statement.setLong(5, epoch)
            statement.setLong(6, epoch - MAX_TRACKING_MINUTES * 60L)
            statement.executeUpdate()
        }
    }

    private fun insertOutcome(symbol: String, close: Double, epoch: Long, horizon: Int) {
        connection.prepareStatement("""INSERT OR IGNORE INTO research_outcomes
            (sample_id, horizon_minutes, observed_price, return_percent, elapsed_minutes,
             maximum_return_percent, minimum_return_percent, observed_at)
            SELECT s.id, ?, ?, (? / s.entry_price - 1.0) * 100.0, (? - s.observed_epoch) / 60.0,
                   x.maximum_return_percent, x.minimum_return_percent, ?
            FROM research_samples s LEFT JOIN research_excursions x ON x.sample_id=s.id
            WHERE s.symbol=? AND s.observed_epoch<=? AND s.observed_epoch>=?""").use { statement ->
            statement.setInt(1, horizon)
            statement.setDouble(2, close)
            statement.setDouble(3, close)
            statement.setLong(4, epoch)
            statement.setLong(5, epoch)
            statement.setString(6, symbol)
            statement.setLong(7, epoch - horizon * 60L)
            statement.setLong(8, epoch - (horizon + OUTCOME_LAG_MINUTES) * 60L)
            statement.executeUpdate()
        }
    }

    private fun family(source: String): String = when {
        source.startsWith("V-Reversal") -> "V-Reversal"
        source.startsWith("Momentum") -> "Momentum"
        source.startsWith("Steady rise") || source.startsWith("Trend") -> "Steady rise"
        source.startsWith("Early recovery") -> "Early recovery"
        else -> "Impulse"
    }

    private fun metric(statement: java.sql.PreparedStatement, index: Int, value: Double?) {
        if (value != null && value.isFinite()) statement.setDouble(index, value) else statement.setNull(index, Types.REAL)
    }

    private companion object {
        const val CONTROL_FAMILY = "Control"
        const val HISTORICAL_SOURCE = "HISTORICAL"
        const val EPISODE_SECONDS = 15 * 60L
        const val MAX_TRACKING_MINUTES = 34
        const val OUTCOME_LAG_MINUTES = 4
        val HORIZONS = listOf(5, 10, 30)
        const val INSERT_SQL = """INSERT OR IGNORE INTO research_samples(
            run_id, symbol, observed_epoch, entry_price, family, direction, accepted, source,
            score, jump_z, range_z, volume_z, rvol, return_1m, return_3m, return_5m, return_10m,
            return_30m, return_60m, range_10m, volatility_30m, vwap_distance, session_high_distance,
            session_low_distance, volume_ratio_10m, trend_efficiency_10m)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        const val HISTORICAL_OUTCOME_SQL = """INSERT OR IGNORE INTO research_outcomes(
            sample_id, horizon_minutes, observed_price, return_percent, elapsed_minutes,
            maximum_return_percent, minimum_return_percent, observed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)"""
    }
}
