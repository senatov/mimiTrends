@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection

internal class SignalOutcomeStore(private val connection: Connection) {
    fun record(symbol: String, currentPrice: Double, highPrice: Double, lowPrice: Double, observedEpoch: Long) {
        if (listOf(currentPrice, highPrice, lowPrice).any { !it.isFinite() || it <= 0.0 }) return
        updateExcursions(symbol.uppercase(), highPrice, lowPrice, observedEpoch)
        insertHorizonOutcomes(symbol.uppercase(), currentPrice, observedEpoch)
    }

    private fun updateExcursions(symbol: String, highPrice: Double, lowPrice: Double, observedEpoch: Long) {
        connection.prepareStatement("""INSERT INTO signal_excursions
            (run_id, symbol, maximum_return_percent, minimum_return_percent, last_observed_at)
            SELECT c.run_id, c.symbol, (? / c.entry_price - 1.0) * 100.0,
                   (? / c.entry_price - 1.0) * 100.0, ?
            FROM scan_candidates c
            WHERE c.symbol=? AND c.published=1 AND c.entry_price>0
              AND c.signal_epoch<=? AND c.signal_epoch>=? AND ? <= c.entry_price * 2.0
            ON CONFLICT(run_id, symbol) DO UPDATE SET
              maximum_return_percent=MAX(signal_excursions.maximum_return_percent, excluded.maximum_return_percent),
              minimum_return_percent=MIN(signal_excursions.minimum_return_percent, excluded.minimum_return_percent),
              last_observed_at=excluded.last_observed_at""").use { statement ->
            statement.setDouble(1, highPrice)
            statement.setDouble(2, lowPrice)
            statement.setLong(3, observedEpoch)
            statement.setString(4, symbol)
            statement.setLong(5, observedEpoch)
            statement.setLong(6, observedEpoch - MAX_TRACKING_MINUTES * 60L)
            statement.setDouble(7, highPrice)
            statement.executeUpdate()
        }
    }

    private fun insertHorizonOutcomes(symbol: String, currentPrice: Double, observedEpoch: Long) {
        connection.prepareStatement("""INSERT OR IGNORE INTO signal_outcomes
            (run_id, symbol, horizon_minutes, entry_price, observed_price, return_percent, observed_at,
             elapsed_minutes, maximum_return_percent, minimum_return_percent)
            SELECT c.run_id, c.symbol, ?, c.entry_price, ?, (? / c.entry_price - 1.0) * 100.0, ?,
                   (? - c.signal_epoch) / 60.0, x.maximum_return_percent, x.minimum_return_percent
            FROM scan_candidates c
            JOIN scan_runs r ON r.id=c.run_id
            LEFT JOIN signal_excursions x ON x.run_id=c.run_id AND x.symbol=c.symbol
            WHERE c.symbol=? AND c.published=1 AND c.entry_price>0 AND c.signal_epoch<=?
              AND c.signal_epoch>=? AND ABS((? / c.entry_price - 1.0) * 100.0)<=?"""
        ).use { statement ->
            for (horizon in HORIZONS_MINUTES) {
                statement.setInt(1, horizon)
                statement.setDouble(2, currentPrice)
                statement.setDouble(3, currentPrice)
                statement.setLong(4, observedEpoch)
                statement.setLong(5, observedEpoch)
                statement.setString(6, symbol)
                statement.setLong(7, observedEpoch - horizon * 60L)
                statement.setLong(8, observedEpoch - (horizon + OUTCOME_MAX_LAG_MINUTES) * 60L)
                statement.setDouble(9, currentPrice)
                statement.setDouble(10, MAX_PLAUSIBLE_RETURN_PERCENT)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private companion object {
        val HORIZONS_MINUTES = listOf(5, 10, 30, 60, 90)
        const val OUTCOME_MAX_LAG_MINUTES = 4
        const val MAX_TRACKING_MINUTES = 94
        const val MAX_PLAUSIBLE_RETURN_PERCENT = 100.0
    }
}