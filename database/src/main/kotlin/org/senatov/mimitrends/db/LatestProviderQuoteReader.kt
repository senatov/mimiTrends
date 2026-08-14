@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import java.sql.Connection
import java.sql.ResultSet

internal object LatestProviderQuoteReader {
    fun load(connection: Connection, symbol: String, notBeforeMillis: Long): ProviderQuoteSnapshot? =
        connection.prepareStatement(
            """SELECT provider, identifier, currency, last, bid, ask, bid_size, ask_size, session_volume,
                session_turnover, average_price, executions, session_high, session_low, previous_close, observed_at
                FROM provider_quotes WHERE symbol=? AND observed_at>=?
                ORDER BY observed_at DESC LIMIT 1"""
        ).use { statement ->
            statement.setString(1, symbol.trim().uppercase())
            statement.setLong(2, notBeforeMillis)
            statement.executeQuery().use { result -> if (result.next()) result.quote(symbol) else null }
        }

    private fun ResultSet.quote(symbol: String) = ProviderQuoteSnapshot(
        provider = getString(1), symbol = symbol.trim().uppercase(), identifier = getString(2), currency = getString(3),
        last = getDouble(4), bid = nullableDouble(5), ask = nullableDouble(6), bidSize = nullableDouble(7),
        askSize = nullableDouble(8), sessionVolume = nullableDouble(9), sessionTurnover = nullableDouble(10),
        averagePrice = nullableDouble(11), executions = getLong(12).let { if (wasNull()) null else it },
        sessionHigh = nullableDouble(13), sessionLow = nullableDouble(14), previousClose = nullableDouble(15),
        observedAtMillis = getLong(16)
    )

    private fun ResultSet.nullableDouble(column: Int): Double? = getDouble(column).let { if (wasNull()) null else it }
}
