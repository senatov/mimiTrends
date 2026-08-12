@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import java.sql.Connection

internal object AllProviderBarsReader {
    fun load(connection: Connection, symbol: String, fromEpochSeconds: Long): List<ProviderMinuteBar> =
        connection.prepareStatement(
            """SELECT provider, identifier, mic, currency, minute_epoch, open, high, low, close, volume,
               volume_status, observed_at FROM provider_minute_bars
               WHERE symbol=? AND minute_epoch>=? ORDER BY minute_epoch, observed_at"""
        ).use { statement ->
            val normalizedSymbol = symbol.trim().uppercase()
            statement.setString(1, normalizedSymbol)
            statement.setLong(2, fromEpochSeconds)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val bar = MinuteBar(normalizedSymbol, result.getLong(5), result.getDouble(6),
                        result.getDouble(7), result.getDouble(8), result.getDouble(9), result.getDouble(10),
                        runCatching { VolumeStatus.valueOf(result.getString(11)) }.getOrDefault(VolumeStatus.MISSING))
                    add(ProviderMinuteBar(result.getString(1), normalizedSymbol, result.getString(2),
                        result.getString(3), result.getString(4), bar, result.getLong(12)))
                }
            } }
        }
}
