@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class MarketRepository(
    private val databasePath: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "mimitrends.db")
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        log.debug(LogTag.DB, "init(databasePath={})", databasePath)
        Files.createDirectories(databasePath.parent)
        connection().use(::migrate)
    }

    fun upsertMinuteBar(bar: MinuteBar) {
        log.debug(LogTag.DB, "upsertMinuteBar(symbol={}, minute={})", bar.symbol, bar.minuteEpochSeconds)
        connection().use { connection ->
            connection.prepareStatement(
                """INSERT INTO minute_bars(symbol, minute_epoch, open, high, low, close, volume)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(symbol, minute_epoch) DO UPDATE SET open=excluded.open,
                   high=excluded.high, low=excluded.low, close=excluded.close, volume=excluded.volume"""
            ).use { statement ->
                statement.setString(1, bar.symbol); statement.setLong(2, bar.minuteEpochSeconds)
                statement.setDouble(3, bar.open); statement.setDouble(4, bar.high); statement.setDouble(5, bar.low)
                statement.setDouble(6, bar.close); statement.setDouble(7, bar.volume); statement.executeUpdate()
            }
        }
    }

    fun loadMinuteBars(symbol: String, fromEpochSeconds: Long): List<MinuteBar> {
        log.debug(LogTag.DB, "loadMinuteBars(symbol={}, from={})", symbol, fromEpochSeconds)
        connection().use { connection ->
            return connection.prepareStatement(
                """SELECT minute_epoch, open, high, low, close, volume FROM minute_bars
                   WHERE symbol = ? AND minute_epoch >= ? ORDER BY minute_epoch"""
            ).use { statement ->
                statement.setString(1, symbol.uppercase()); statement.setLong(2, fromEpochSeconds)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(MinuteBar(symbol.uppercase(), result.getLong(1), result.getDouble(2),
                            result.getDouble(3), result.getDouble(4), result.getDouble(5), result.getDouble(6)))
                    }
                }
            }
        }
    }

    private fun connection(): Connection {
        log.debug(LogTag.DB, "connection()")
        return DriverManager.getConnection("jdbc:sqlite:$databasePath").also { connection ->
            connection.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 3000") }
        }
    }

    private fun migrate(connection: Connection) {
        log.debug(LogTag.DB, "migrate()")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS minute_bars (
                    symbol TEXT NOT NULL, minute_epoch INTEGER NOT NULL, open REAL NOT NULL,
                    high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL,
                    PRIMARY KEY(symbol, minute_epoch)
                )"""
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_minute_symbol_time ON minute_bars(symbol, minute_epoch)")
        }
    }
}
