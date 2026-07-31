@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.CompanyProfile
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MarketRepository(
    private val databasePath: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "mimitrends.db")
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()
    private val pendingLock = Any()
    private val pending = linkedMapOf<Pair<String, Long>, MinuteBar>()
    private val closed = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-sqlite-writer").apply { isDaemon = true }
    }
    private val connection: Connection

    init {
        log.debug(LogTag.DB, "init(databasePath={})", databasePath)
        Files.createDirectories(databasePath.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")
        configure(connection)
        migrate(connection)
        writer.scheduleWithFixedDelay(::flushSafely, 1, 1, TimeUnit.SECONDS)
    }

    fun upsertMinuteBar(bar: MinuteBar) {
        log.trace(LogTag.DB, "upsertMinuteBar(symbol={}, minute={})", bar.symbol, bar.minuteEpochSeconds)
        check(!closed.get()) { "MarketRepository is closed" }
        synchronized(pendingLock) { pending[bar.symbol to bar.minuteEpochSeconds] = bar }
    }

    fun loadMinuteBars(symbol: String, fromEpochSeconds: Long): List<MinuteBar> {
        log.debug(LogTag.DB, "loadMinuteBars(symbol={}, from={})", symbol, fromEpochSeconds)
        flushPending()
        return lock.withLock {
            connection.prepareStatement(
                """SELECT minute_epoch, open, high, low, close, volume FROM minute_bars
                   WHERE symbol = ? AND minute_epoch >= ? ORDER BY minute_epoch"""
            ).use { statement ->
                val normalized = symbol.uppercase()
                statement.setString(1, normalized); statement.setLong(2, fromEpochSeconds)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(MinuteBar(normalized, result.getLong(1), result.getDouble(2),
                            result.getDouble(3), result.getDouble(4), result.getDouble(5), result.getDouble(6)))
                    }
                }
            }
        }
    }

    fun latestMinuteEpoch(symbol: String): Long? {
        log.debug(LogTag.DB, "latestMinuteEpoch(symbol={})", symbol)
        flushPending()
        return lock.withLock {
            connection.prepareStatement("SELECT MAX(minute_epoch) FROM minute_bars WHERE symbol = ?").use { statement ->
                statement.setString(1, symbol.trim().uppercase())
                statement.executeQuery().use { result ->
                    if (result.next()) result.getLong(1).takeUnless { result.wasNull() } else null
                }
            }
        }
    }

    fun loadCompanyProfile(symbol: String): CompanyProfile? {
        log.debug(LogTag.DB, "loadCompanyProfile(symbol={})", symbol)
        return lock.withLock {
            connection.prepareStatement(
                "SELECT name, exchange, logo_url, logo, updated_at FROM company_profiles WHERE symbol = ?"
            ).use { statement ->
                val normalized = symbol.trim().uppercase()
                statement.setString(1, normalized)
                statement.executeQuery().use { result ->
                    if (!result.next()) null else CompanyProfile(
                        symbol = normalized,
                        name = result.getString(1),
                        exchange = result.getString(2),
                        logoUrl = result.getString(3),
                        logoBytes = result.getBytes(4),
                        updatedAtMillis = result.getLong(5)
                    )
                }
            }
        }
    }

    fun upsertCompanyProfile(profile: CompanyProfile) {
        log.debug(LogTag.DB, "upsertCompanyProfile(symbol={}, logoBytes={})", profile.symbol, profile.logoBytes?.size ?: 0)
        check(!closed.get()) { "MarketRepository is closed" }
        lock.withLock {
            connection.prepareStatement(UPSERT_PROFILE_SQL).use { statement ->
                statement.setString(1, profile.symbol.trim().uppercase())
                statement.setString(2, profile.name)
                statement.setString(3, profile.exchange)
                statement.setString(4, profile.logoUrl)
                statement.setBytes(5, profile.logoBytes)
                statement.setLong(6, profile.updatedAtMillis)
                statement.executeUpdate()
            }
        }
    }

    fun flushPending(): Int {
        log.trace(LogTag.DB, "flushPending()")
        val batch = synchronized(pendingLock) {
            if (pending.isEmpty()) return 0
            pending.values.toList().also { pending.clear() }
        }
        return try {
            lock.withLock {
                connection.autoCommit = false
                try {
                    connection.prepareStatement(UPSERT_SQL).use { statement ->
                        batch.forEach { bar ->
                            statement.setString(1, bar.symbol); statement.setLong(2, bar.minuteEpochSeconds)
                            statement.setDouble(3, bar.open); statement.setDouble(4, bar.high); statement.setDouble(5, bar.low)
                            statement.setDouble(6, bar.close); statement.setDouble(7, bar.volume); statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.commit()
                } catch (error: Exception) {
                    connection.rollback(); throw error
                } finally {
                    connection.autoCommit = true
                }
            }
            log.debug(LogTag.DB, "minute-bar batch stored size={}", batch.size)
            batch.size
        } catch (error: Exception) {
            synchronized(pendingLock) { batch.forEach { pending.putIfAbsent(it.symbol to it.minuteEpochSeconds, it) } }
            throw error
        }
    }

    override fun close() {
        log.debug(LogTag.DB, "close()")
        if (!closed.compareAndSet(false, true)) return
        writer.shutdown()
        runCatching { flushPending() }.onFailure { log.error(LogTag.DB, "final database flush failed", it) }
        lock.withLock { connection.close() }
    }

    private fun flushSafely() {
        log.trace(LogTag.DB, "flushSafely()")
        if (!closed.get()) runCatching(::flushPending).onFailure { log.error(LogTag.DB, "background database flush failed", it) }
    }

    private fun configure(connection: Connection) {
        log.debug(LogTag.DB, "configure()")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = NORMAL")
            statement.execute("PRAGMA busy_timeout = 5000")
            statement.execute("PRAGMA temp_store = MEMORY")
            statement.execute("PRAGMA foreign_keys = ON")
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
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS company_profiles (
                    symbol TEXT PRIMARY KEY, name TEXT NOT NULL, exchange TEXT NOT NULL,
                    logo_url TEXT, logo BLOB, updated_at INTEGER NOT NULL
                )"""
            )
        }
    }

    private companion object {
        const val UPSERT_SQL = """INSERT INTO minute_bars(symbol, minute_epoch, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, minute_epoch) DO UPDATE SET
            open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close, volume=excluded.volume"""
        const val UPSERT_PROFILE_SQL = """INSERT INTO company_profiles(symbol, name, exchange, logo_url, logo, updated_at)
            VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET
            name=excluded.name, exchange=excluded.exchange, logo_url=excluded.logo_url,
            logo=excluded.logo, updated_at=excluded.updated_at"""
    }
}
