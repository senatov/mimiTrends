@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection

internal object UserWatchlistStore {
    fun migrate(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS user_watchlist (
                    symbol TEXT PRIMARY KEY,
                    added_at INTEGER NOT NULL
                )"""
            )
        }
    }

    fun load(connection: Connection): Set<String> = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT symbol FROM user_watchlist ORDER BY added_at, symbol").use { result ->
            buildSet { while (result.next()) add(result.getString(1).trim().uppercase()) }
        }
    }

    fun add(connection: Connection, symbol: String) {
        connection.prepareStatement(
            "INSERT OR IGNORE INTO user_watchlist(symbol, added_at) VALUES (?, ?)"
        ).use { statement ->
            statement.setString(1, symbol.trim().uppercase())
            statement.setLong(2, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    fun remove(connection: Connection, symbol: String) {
        connection.prepareStatement("DELETE FROM user_watchlist WHERE symbol=?").use { statement ->
            statement.setString(1, symbol.trim().uppercase())
            statement.executeUpdate()
        }
    }
}
