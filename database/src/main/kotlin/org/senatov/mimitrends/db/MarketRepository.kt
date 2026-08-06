@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.VolumeStatus
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MarketRepository(
    private val database: EmbeddedDatabase = EmbeddedDatabase.open()
) : AutoCloseable {
    constructor(databasePath: Path) : this(EmbeddedDatabase.open(databasePath))

    private val log = LoggerFactory.getLogger(javaClass)
    private val pendingLock = Any()
    private val pending = linkedMapOf<Pair<String, Long>, MinuteBar>()
    private val closed = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-sqlite-writer").apply { isDaemon = true }
    }
    private val connection: Connection = database.connection

    init {
        log.debug(LogTag.DB, "init()")
        database.locked { migrate(it) }
        writer.scheduleWithFixedDelay(::flushSafely, 1, 1, TimeUnit.SECONDS)
    }

    fun upsertMinuteBar(bar: MinuteBar) {
        log.trace(LogTag.DB, "upsertMinuteBar(symbol={}, minute={})", bar.symbol, bar.minuteEpochSeconds)
        check(!closed.get()) { "MarketRepository is closed" }
        val normalized = bar.copy(symbol = bar.symbol.trim().uppercase())
        synchronized(pendingLock) { pending[normalized.symbol to normalized.minuteEpochSeconds] = normalized }
    }

    fun loadMinuteBars(symbol: String, fromEpochSeconds: Long): List<MinuteBar> {
        log.debug(LogTag.DB, "loadMinuteBars(symbol={}, from={})", symbol, fromEpochSeconds)
        flushPending()
        return database.locked {
            connection.prepareStatement(
                """SELECT minute_epoch, open, high, low, close, volume, volume_status FROM minute_bars
                   WHERE symbol = ? AND minute_epoch >= ? ORDER BY minute_epoch"""
            ).use { statement ->
                val normalized = symbol.uppercase()
                statement.setString(1, normalized); statement.setLong(2, fromEpochSeconds)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(MinuteBar(normalized, result.getLong(1), result.getDouble(2),
                            result.getDouble(3), result.getDouble(4), result.getDouble(5), result.getDouble(6),
                            runCatching { VolumeStatus.valueOf(result.getString(7)) }.getOrDefault(VolumeStatus.MISSING)))
                    }
                }
            }
        }
    }

    fun latestMinuteEpoch(symbol: String): Long? {
        log.debug(LogTag.DB, "latestMinuteEpoch(symbol={})", symbol)
        flushPending()
        return database.locked {
            connection.prepareStatement("SELECT MAX(minute_epoch) FROM minute_bars WHERE symbol = ?").use { statement ->
                statement.setString(1, symbol.trim().uppercase())
                statement.executeQuery().use { result ->
                    if (result.next()) result.getLong(1).takeUnless { result.wasNull() } else null
                }
            }
        }
    }

    fun listSymbols(): List<String> {
        flushPending()
        return database.locked {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT DISTINCT symbol FROM minute_bars ORDER BY symbol").use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }
    }

    fun loadProviderInstrument(provider: String, symbol: String): ProviderInstrument? = database.locked {
        connection.prepareStatement(
            """SELECT identifier, mic, currency, resolved_name, updated_at FROM provider_instruments
               WHERE provider=? AND symbol=?"""
        ).use { statement ->
            val normalizedProvider = provider.trim().uppercase()
            val normalizedSymbol = symbol.trim().uppercase()
            statement.setString(1, normalizedProvider); statement.setString(2, normalizedSymbol)
            statement.executeQuery().use { result ->
                if (!result.next()) null else ProviderInstrument(
                    normalizedProvider, normalizedSymbol, result.getString(1), result.getString(2),
                    result.getString(3), result.getString(4), result.getLong(5)
                )
            }
        }
    }

    fun deleteProviderInstrument(provider: String, symbol: String): Boolean = database.locked {
        connection.prepareStatement("DELETE FROM provider_instruments WHERE provider=? AND symbol=?").use { statement ->
            statement.setString(1, provider.trim().uppercase())
            statement.setString(2, symbol.trim().uppercase())
            statement.executeUpdate() > 0
        }
    }

    fun upsertProviderInstrument(value: ProviderInstrument) = database.locked {
        connection.prepareStatement(UPSERT_PROVIDER_INSTRUMENT_SQL).use { statement ->
            statement.setString(1, value.provider.trim().uppercase())
            statement.setString(2, value.symbol.trim().uppercase())
            statement.setString(3, value.identifier)
            statement.setString(4, value.mic)
            statement.setString(5, value.currency)
            statement.setString(6, value.resolvedName)
            statement.setLong(7, value.updatedAtMillis)
            statement.executeUpdate()
        }
    }

    /** Returns true only when the observation inserted a bar or advanced an existing minute. */
    fun upsertProviderMinuteBar(value: ProviderMinuteBar): Boolean = database.locked {
        connection.prepareStatement(UPSERT_PROVIDER_BAR_SQL).use { statement ->
            val bar = value.bar
            statement.setString(1, value.provider.trim().uppercase())
            statement.setString(2, value.symbol.trim().uppercase())
            statement.setString(3, value.identifier)
            statement.setString(4, value.mic)
            statement.setString(5, value.currency)
            statement.setLong(6, bar.minuteEpochSeconds)
            statement.setDouble(7, bar.open); statement.setDouble(8, bar.high); statement.setDouble(9, bar.low)
            statement.setDouble(10, bar.close); statement.setDouble(11, bar.volume)
            statement.setString(12, bar.volumeStatus.name); statement.setLong(13, value.observedAtMillis)
            statement.executeUpdate() > 0
        }
    }

    fun upsertProviderQuote(value: ProviderQuoteSnapshot): Boolean = database.locked {
        connection.prepareStatement(UPSERT_PROVIDER_QUOTE_SQL).use { statement ->
            statement.setString(1, value.provider.trim().uppercase()); statement.setString(2, value.symbol.trim().uppercase())
            statement.setString(3, value.identifier); statement.setString(4, value.currency); statement.setDouble(5, value.last)
            statement.setObject(6, value.bid); statement.setObject(7, value.ask); statement.setObject(8, value.bidSize)
            statement.setObject(9, value.askSize); statement.setObject(10, value.sessionVolume)
            statement.setObject(11, value.sessionTurnover); statement.setObject(12, value.averagePrice)
            statement.setObject(13, value.executions); statement.setObject(14, value.sessionHigh)
            statement.setObject(15, value.sessionLow); statement.setObject(16, value.previousClose)
            statement.setLong(17, value.observedAtMillis); statement.executeUpdate() > 0
        }
    }

    fun loadProviderMinuteBars(provider: String, symbol: String, fromEpochSeconds: Long): List<ProviderMinuteBar> =
        database.locked {
            connection.prepareStatement(
                """SELECT identifier, mic, currency, minute_epoch, open, high, low, close, volume,
                   volume_status, observed_at FROM provider_minute_bars
                   WHERE provider=? AND symbol=? AND minute_epoch>=? ORDER BY minute_epoch"""
            ).use { statement ->
                val normalizedProvider = provider.trim().uppercase()
                val normalizedSymbol = symbol.trim().uppercase()
                statement.setString(1, normalizedProvider); statement.setString(2, normalizedSymbol)
                statement.setLong(3, fromEpochSeconds)
                statement.executeQuery().use { result -> buildList {
                    while (result.next()) {
                        val bar = MinuteBar(normalizedSymbol, result.getLong(4), result.getDouble(5),
                            result.getDouble(6), result.getDouble(7), result.getDouble(8), result.getDouble(9),
                            runCatching { VolumeStatus.valueOf(result.getString(10)) }.getOrDefault(VolumeStatus.MISSING))
                        add(ProviderMinuteBar(normalizedProvider, normalizedSymbol, result.getString(1),
                            result.getString(2), result.getString(3), bar, result.getLong(11)))
                    }
                } }
            }
        }

    fun loadLatestProviderMinuteBar(symbol: String, notBeforeEpochSeconds: Long): ProviderMinuteBar? =
        database.locked { LatestProviderBarReader.load(connection, symbol, notBeforeEpochSeconds) }

    fun loadCompanyProfile(symbol: String): CompanyProfile? {
        log.debug(LogTag.DB, "loadCompanyProfile(symbol={})", symbol)
        return database.locked {
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
        database.locked {
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
            database.locked {
                connection.autoCommit = false
                try {
                    connection.prepareStatement(UPSERT_SQL).use { statement ->
                        batch.forEach { bar ->
                            statement.setString(1, bar.symbol); statement.setLong(2, bar.minuteEpochSeconds)
                            statement.setDouble(3, bar.open); statement.setDouble(4, bar.high); statement.setDouble(5, bar.low)
                            statement.setDouble(6, bar.close); statement.setDouble(7, bar.volume)
                            statement.setString(8, bar.volumeStatus.name); statement.addBatch()
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
        database.close()
    }

    private fun flushSafely() {
        log.trace(LogTag.DB, "flushSafely()")
        if (!closed.get()) runCatching(::flushPending).onFailure { log.error(LogTag.DB, "background database flush failed", it) }
    }

    private fun migrate(connection: Connection) {
        log.debug(LogTag.DB, "migrate()")
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS minute_bars (
                        symbol TEXT NOT NULL, minute_epoch INTEGER NOT NULL, open REAL NOT NULL,
                        high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL,
                        volume_status TEXT NOT NULL DEFAULT 'MISSING',
                        PRIMARY KEY(symbol, minute_epoch)
                    )"""
                )
                val columns = statement.executeQuery("PRAGMA table_info(minute_bars)").use { result ->
                    buildSet { while (result.next()) add(result.getString("name")) }
                }
                if ("volume_status" !in columns) {
                    statement.executeUpdate("ALTER TABLE minute_bars ADD COLUMN volume_status TEXT NOT NULL DEFAULT 'MISSING'")
                    statement.executeUpdate(
                        "UPDATE minute_bars SET volume_status = CASE WHEN volume > 0 THEN 'REPORTED' ELSE 'MISSING' END"
                    )
                }
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_minute_symbol_time ON minute_bars(symbol, minute_epoch)")
                val removedSnapshots = statement.executeUpdate(
                    "DELETE FROM minute_bars WHERE minute_epoch % 60 != 0 AND volume <= 0"
                )
                if (removedSnapshots > 0) {
                    log.info(LogTag.DB, "removed malformed zero-volume quote snapshots count={}", removedSnapshots)
                }
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS company_profiles (
                        symbol TEXT PRIMARY KEY, name TEXT NOT NULL, exchange TEXT NOT NULL,
                        logo_url TEXT, logo BLOB, updated_at INTEGER NOT NULL
                    )"""
                )
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS provider_instruments (
                        provider TEXT NOT NULL, symbol TEXT NOT NULL, identifier TEXT NOT NULL,
                        mic TEXT NOT NULL, currency TEXT NOT NULL, resolved_name TEXT NOT NULL,
                        updated_at INTEGER NOT NULL, PRIMARY KEY(provider, symbol)
                    )"""
                )
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS provider_minute_bars (
                        provider TEXT NOT NULL, symbol TEXT NOT NULL, identifier TEXT NOT NULL,
                        mic TEXT NOT NULL, currency TEXT NOT NULL, minute_epoch INTEGER NOT NULL,
                        open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
                        volume REAL NOT NULL, volume_status TEXT NOT NULL, observed_at INTEGER NOT NULL,
                        PRIMARY KEY(provider, symbol, minute_epoch)
                    )"""
                )
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS provider_quotes (
                        provider TEXT NOT NULL, symbol TEXT NOT NULL, identifier TEXT NOT NULL, currency TEXT NOT NULL,
                        last REAL NOT NULL, bid REAL, ask REAL, bid_size REAL, ask_size REAL, session_volume REAL,
                        session_turnover REAL, average_price REAL, executions INTEGER, session_high REAL,
                        session_low REAL, previous_close REAL, observed_at INTEGER NOT NULL,
                        PRIMARY KEY(provider, symbol)
                    )"""
                )
                statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_provider_bars_symbol_time ON provider_minute_bars(symbol, minute_epoch)"
                )
                // Remove the temporary generated-monogram source used by an older build so genuine
                // cached company favicons are fetched on the next visible table render.
                statement.executeUpdate(
                    "UPDATE company_profiles SET logo_url = NULL, logo = NULL WHERE logo_url LIKE 'https://img.loadlogo.com/%'"
                )
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private companion object {
        const val UPSERT_SQL = """INSERT INTO minute_bars(symbol, minute_epoch, open, high, low, close, volume, volume_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, minute_epoch) DO UPDATE SET
            open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close,
            volume=excluded.volume, volume_status=excluded.volume_status"""
        const val UPSERT_PROFILE_SQL = """INSERT INTO company_profiles(symbol, name, exchange, logo_url, logo, updated_at)
            VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET
            name=excluded.name, exchange=excluded.exchange, logo_url=excluded.logo_url,
            logo=excluded.logo, updated_at=excluded.updated_at"""
        const val UPSERT_PROVIDER_INSTRUMENT_SQL = """INSERT INTO provider_instruments(
            provider, symbol, identifier, mic, currency, resolved_name, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(provider, symbol) DO UPDATE SET identifier=excluded.identifier, mic=excluded.mic,
            currency=excluded.currency, resolved_name=excluded.resolved_name, updated_at=excluded.updated_at"""
        const val UPSERT_PROVIDER_BAR_SQL = """INSERT INTO provider_minute_bars(
            provider, symbol, identifier, mic, currency, minute_epoch, open, high, low, close, volume,
            volume_status, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(provider, symbol, minute_epoch) DO UPDATE SET
            high=MAX(provider_minute_bars.high, excluded.high),
            low=MIN(provider_minute_bars.low, excluded.low), close=excluded.close,
            volume=CASE WHEN excluded.volume_status='REPORTED' THEN excluded.volume ELSE provider_minute_bars.volume END,
            volume_status=CASE WHEN excluded.volume_status='REPORTED' THEN excluded.volume_status ELSE provider_minute_bars.volume_status END,
            observed_at=excluded.observed_at WHERE excluded.observed_at > provider_minute_bars.observed_at"""
        const val UPSERT_PROVIDER_QUOTE_SQL = """INSERT INTO provider_quotes(provider, symbol, identifier, currency,
            last, bid, ask, bid_size, ask_size, session_volume, session_turnover, average_price, executions,
            session_high, session_low, previous_close, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(provider, symbol) DO UPDATE SET identifier=excluded.identifier, currency=excluded.currency,
            last=excluded.last, bid=excluded.bid, ask=excluded.ask, bid_size=excluded.bid_size, ask_size=excluded.ask_size,
            session_volume=excluded.session_volume, session_turnover=excluded.session_turnover,
            average_price=excluded.average_price, executions=excluded.executions, session_high=excluded.session_high,
            session_low=excluded.session_low, previous_close=excluded.previous_close, observed_at=excluded.observed_at
            WHERE excluded.observed_at > provider_quotes.observed_at"""
    }
}
