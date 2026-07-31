@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.Candle
import org.senatov.mimitrends.model.MarketSnapshot
import org.senatov.mimitrends.model.Quote
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit

class MarketRepository(
    private val databasePath: Path = Path.of(
        System.getProperty("user.home"), ".mimi", "trends", "mimitrends.db"
    )
) {
    private val log = LoggerFactory.getLogger(MarketRepository::class.java)

    init {
        log.debug(LogTag.DB, "init(databasePath={})", databasePath)
        Files.createDirectories(databasePath.parent)
        connection().use { connection -> migrate(connection) }
    }

    fun save(snapshot: MarketSnapshot) {
        log.debug(LogTag.DB, "save(symbol={}, candles={})", snapshot.symbol, snapshot.candles.size)
        connection().use { connection ->
            connection.autoCommit = false
            try {
                saveQuote(connection, snapshot)
                snapshot.candles.forEach { savePoint(connection, snapshot.symbol, it, "candle") }
                savePoint(connection, snapshot.symbol, Candle(Instant.now().epochSecond, snapshot.quote.current), "quote")
                connection.commit()
                log.info(LogTag.DB, "snapshot stored symbol={} candles={}", snapshot.symbol, snapshot.candles.size)
            } catch (error: Exception) {
                connection.rollback()
                log.error(LogTag.DB, "snapshot store failed symbol={}", snapshot.symbol, error)
                throw error
            }
        }
    }

    fun load(symbol: String, days: Long): MarketSnapshot? {
        log.debug(LogTag.DB, "load(symbol={}, days={})", symbol, days)
        connection().use { connection ->
            val quote = loadQuote(connection, symbol) ?: return null
            val from = Instant.now().minus(days, ChronoUnit.DAYS).epochSecond
            val candles = connection.prepareStatement(
                "SELECT timestamp, close FROM price_points WHERE symbol = ? AND timestamp >= ? ORDER BY timestamp"
            ).use { statement ->
                statement.setString(1, symbol)
                statement.setLong(2, from)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(Candle(result.getLong(1), result.getDouble(2)))
                    }
                }
            }
            log.info(LogTag.DB, "cache loaded symbol={} points={}", symbol, candles.size)
            return MarketSnapshot(symbol, quote = quote, candles = candles, fromCache = true)
        }
    }

    private fun connection(): Connection {
        log.debug(LogTag.DB, "connection()")
        return DriverManager.getConnection("jdbc:sqlite:$databasePath").also { connection ->
            connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            connection.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
            connection.createStatement().use { it.execute("PRAGMA busy_timeout = 3000") }
        }
    }

    private fun migrate(connection: Connection) {
        log.debug(LogTag.DB, "migrate()")
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS latest_quotes (
                    symbol TEXT PRIMARY KEY, current REAL NOT NULL, change REAL NOT NULL,
                    percent_change REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL,
                    open REAL NOT NULL, previous_close REAL NOT NULL, updated_at INTEGER NOT NULL
                )"""
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS price_points (
                    symbol TEXT NOT NULL, timestamp INTEGER NOT NULL, close REAL NOT NULL,
                    source TEXT NOT NULL, PRIMARY KEY(symbol, timestamp, source)
                )"""
            )
            statement.executeUpdate(
                "CREATE INDEX IF NOT EXISTS idx_price_symbol_time ON price_points(symbol, timestamp)"
            )
        }
    }

    private fun saveQuote(connection: Connection, snapshot: MarketSnapshot) {
        log.debug(LogTag.DB, "saveQuote(symbol={})", snapshot.symbol)
        connection.prepareStatement(
            """INSERT INTO latest_quotes VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(symbol) DO UPDATE SET current=excluded.current, change=excluded.change,
               percent_change=excluded.percent_change, high=excluded.high, low=excluded.low,
               open=excluded.open, previous_close=excluded.previous_close, updated_at=excluded.updated_at"""
        ).use { statement ->
            val quote = snapshot.quote
            statement.setString(1, snapshot.symbol)
            statement.setDouble(2, quote.current)
            statement.setDouble(3, quote.change)
            statement.setDouble(4, quote.percentChange)
            statement.setDouble(5, quote.high)
            statement.setDouble(6, quote.low)
            statement.setDouble(7, quote.open)
            statement.setDouble(8, quote.previousClose)
            statement.setLong(9, Instant.now().epochSecond)
            statement.executeUpdate()
        }
    }

    private fun savePoint(connection: Connection, symbol: String, candle: Candle, source: String) {
        log.debug(LogTag.DB, "savePoint(symbol={}, timestamp={}, source={})", symbol, candle.timestampSeconds, source)
        connection.prepareStatement(
            "INSERT OR REPLACE INTO price_points(symbol, timestamp, close, source) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, symbol)
            statement.setLong(2, candle.timestampSeconds)
            statement.setDouble(3, candle.close)
            statement.setString(4, source)
            statement.executeUpdate()
        }
    }

    private fun loadQuote(connection: Connection, symbol: String): Quote? {
        log.debug(LogTag.DB, "loadQuote(symbol={})", symbol)
        connection.prepareStatement(
            "SELECT current, change, percent_change, high, low, open, previous_close FROM latest_quotes WHERE symbol = ?"
        ).use { statement ->
            statement.setString(1, symbol)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                return Quote(
                    result.getDouble(1), result.getDouble(2), result.getDouble(3),
                    result.getDouble(4), result.getDouble(5), result.getDouble(6), result.getDouble(7)
                )
            }
        }
    }
}
