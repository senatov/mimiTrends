@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.isValidMinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.BrokerTrade
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.ln1p

class AnalyticsRepository(
    databasePath: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "mimitrends.db")
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val lock = ReentrantLock()
    private val connection: Connection
    private val brokerTransactions: BrokerTransactionStore

    init {
        Files.createDirectories(databasePath.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")
        connection.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA synchronous=NORMAL")
            it.execute("PRAGMA busy_timeout=5000")
            it.execute("PRAGMA foreign_keys=ON")
            it.execute("PRAGMA temp_store=MEMORY")
            it.execute("PRAGMA cache_size=-20000")
            it.execute("PRAGMA mmap_size=268435456")
        }
        migrate()
        brokerTransactions = BrokerTransactionStore(connection)
    }

    fun upsertInstrument(value: InstrumentMetadata) = locked {
        connection.prepareStatement(UPSERT_INSTRUMENT).use { s ->
            s.setString(1, value.symbol.uppercase()); s.setString(2, value.name); s.setString(3, value.exchange)
            s.setString(4, value.currency); s.setString(5, value.timezone); s.setString(6, value.isin)
            s.setString(7, value.wkn); s.setString(8, value.aliases); s.setInt(9, if (value.tradable) 1 else 0)
            s.setLong(10, value.updatedAtMillis); s.executeUpdate()
        }
    }

    fun upsertCorporateAction(value: CorporateAction) = locked {
        connection.prepareStatement("""INSERT INTO corporate_actions
            (symbol, action_type, effective_epoch, ratio, amount, currency, source)
            VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, action_type, effective_epoch) DO UPDATE SET
            ratio=excluded.ratio, amount=excluded.amount, currency=excluded.currency, source=excluded.source""").use { s ->
            s.setString(1, value.symbol.uppercase()); s.setString(2, value.actionType); s.setLong(3, value.effectiveEpochSeconds)
            s.setObject(4, value.ratio); s.setObject(5, value.amount); s.setString(6, value.currency)
            s.setString(7, value.source); s.executeUpdate()
        }
    }

    fun recordFxRate(base: String, quote: String, rate: Double, source: String, epochSeconds: Long = Instant.now().epochSecond) = locked {
        connection.prepareStatement("""INSERT INTO fx_rates(base_currency, quote_currency, rate_epoch, rate, source)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT(base_currency, quote_currency, rate_epoch) DO UPDATE SET rate=excluded.rate, source=excluded.source""").use { s ->
            s.setString(1, base); s.setString(2, quote); s.setLong(3, epochSeconds / 86_400 * 86_400)
            s.setDouble(4, rate); s.setString(5, source); s.executeUpdate()
        }
    }

    fun recordDataQuality(symbol: String, source: String, status: String, latestEpoch: Long?, barCount: Int, note: String? = null) = locked {
        connection.prepareStatement("""INSERT INTO data_quality(symbol, source, observed_at, latest_bar_epoch, bar_count, status, note)
            VALUES (?, ?, ?, ?, ?, ?, ?)""").use { s ->
            s.setString(1, symbol.uppercase()); s.setString(2, source); s.setLong(3, Instant.now().epochSecond)
            s.setObject(4, latestEpoch); s.setInt(5, barCount); s.setString(6, status); s.setString(7, note); s.executeUpdate()
        }
    }

    fun refreshDerived(symbol: String, bars: List<MinuteBar>, source: String) {
        if (bars.isEmpty()) return
        val normalized = symbol.uppercase()
        val clean = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        if (clean.isEmpty()) return
        locked {
            transaction {
                upsertSessions(normalized, clean, source)
                upsertAggregates(normalized, clean)
                upsertBaselines(normalized, clean)
            }
        }
    }

    fun beginScan(region: String, requestedSymbols: Int, intervalSeconds: Long): Long = locked {
        connection.prepareStatement("""INSERT INTO scan_runs(started_at, region, requested_symbols, interval_seconds, status)
            VALUES (?, ?, ?, ?, 'RUNNING')""", Statement.RETURN_GENERATED_KEYS).use { s ->
            s.setLong(1, Instant.now().epochSecond); s.setString(2, region); s.setInt(3, requestedSymbols); s.setLong(4, intervalSeconds)
            s.executeUpdate(); s.generatedKeys.use { keys -> check(keys.next()); keys.getLong(1) }
        }
    }

    fun recordScanCandidate(runId: Long, symbol: String, result: ScanResult?, rejectionReason: String?, source: String) = locked {
        connection.prepareStatement("""INSERT INTO scan_candidates
            (run_id, symbol, evaluated_at, signal_epoch, accepted, published, rejection_reason, signal, score, change_10m,
             jump_z, range_z, volume_z, rvol, price, entry_price, turnover, source)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(run_id, symbol) DO UPDATE SET signal_epoch=excluded.signal_epoch,
            accepted=excluded.accepted, rejection_reason=excluded.rejection_reason,
            signal=excluded.signal, score=excluded.score, change_10m=excluded.change_10m, jump_z=excluded.jump_z,
            range_z=excluded.range_z, volume_z=excluded.volume_z, rvol=excluded.rvol, price=excluded.price,
            entry_price=excluded.entry_price, turnover=excluded.turnover, source=excluded.source""").use { s ->
            s.setLong(1, runId); s.setString(2, symbol.uppercase()); s.setLong(3, Instant.now().epochSecond)
            fun metric(index: Int, value: Double?) {
                if (value != null && value.isFinite()) s.setDouble(index, value) else s.setNull(index, Types.REAL)
            }
            if (result != null) s.setLong(4, result.signalEpochMillis / 1_000L) else s.setNull(4, Types.INTEGER)
            s.setInt(5, if (result != null) 1 else 0); s.setString(6, rejectionReason); s.setString(7, result?.signalSource)
            metric(8, result?.anomalyScore); metric(9, result?.windowChangePercent); metric(10, result?.priceAnomaly)
            metric(11, result?.rangeAnomaly); metric(12, result?.volumeAnomaly); metric(13, result?.relativeVolume)
            metric(14, result?.price); metric(15, result?.signalPrice); metric(16, result?.sessionTurnover)
            s.setString(17, source); s.executeUpdate()
        }
    }

    fun recordSignalOutcomes(symbol: String, currentPrice: Double, observedEpoch: Long) = locked {
        if (!currentPrice.isFinite() || currentPrice <= 0.0) return@locked
        connection.prepareStatement("""INSERT OR IGNORE INTO signal_outcomes
            (run_id, symbol, horizon_minutes, entry_price, observed_price, return_percent, observed_at, elapsed_minutes)
            SELECT c.run_id, c.symbol, ?, c.entry_price, ?, (? / c.entry_price - 1.0) * 100.0, ?,
                   (? - c.signal_epoch) / 60.0
            FROM scan_candidates c JOIN scan_runs r ON r.id=c.run_id
            WHERE c.symbol=? AND c.published=1 AND c.entry_price>0 AND c.signal_epoch<=?
              AND c.signal_epoch>=?""").use { s ->
            for (horizon in listOf(5, 10, 30)) {
                s.setInt(1, horizon); s.setDouble(2, currentPrice); s.setDouble(3, currentPrice)
                s.setLong(4, observedEpoch); s.setLong(5, observedEpoch); s.setString(6, symbol.uppercase())
                s.setLong(7, observedEpoch - horizon * 60L)
                s.setLong(8, observedEpoch - (horizon + OUTCOME_MAX_LAG_MINUTES) * 60L); s.addBatch()
            }
            s.executeBatch()
        }
    }

    fun completeScan(runId: Long, publishedSymbols: Collection<String>, failures: Int) = locked {
        transaction {
            if (publishedSymbols.isNotEmpty()) connection.prepareStatement(
                "UPDATE scan_candidates SET published=1 WHERE run_id=? AND symbol=?"
            ).use { s -> publishedSymbols.forEach { s.setLong(1, runId); s.setString(2, it.uppercase()); s.addBatch() }; s.executeBatch() }
            connection.prepareStatement("""UPDATE scan_runs SET completed_at=?, evaluated_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=?),
                accepted_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=? AND accepted=1), published_symbols=?, failures=?, status='COMPLETE'
                WHERE id=?""").use { s ->
                s.setLong(1, Instant.now().epochSecond); s.setLong(2, runId); s.setLong(3, runId)
                s.setInt(4, publishedSymbols.size); s.setInt(5, failures); s.setLong(6, runId); s.executeUpdate()
            }
        }
    }

    fun loadAggregatedBars(symbol: String, resolutionMinutes: Int, fromEpoch: Long): List<AggregatedBar> = locked {
        connection.prepareStatement("""SELECT bucket_epoch, open, high, low, close, volume FROM aggregate_bars
            WHERE symbol=? AND resolution_minutes=? AND bucket_epoch>=? ORDER BY bucket_epoch""").use { s ->
            s.setString(1, symbol.uppercase()); s.setInt(2, resolutionMinutes); s.setLong(3, fromEpoch)
            s.executeQuery().use { r -> buildList { while (r.next()) add(AggregatedBar(symbol.uppercase(), resolutionMinutes,
                r.getLong(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6))) } }
        }
    }

    fun loadLatestPublishedResults(limit: Int): List<ScanResult> = locked {
        connection.prepareStatement("""SELECT symbol, price, entry_price, score, jump_z, range_z, volume_z, rvol,
            change_10m, turnover, signal, evaluated_at, signal_epoch
            FROM scan_candidates
            WHERE run_id=(SELECT MAX(run_id) FROM scan_candidates WHERE published=1) AND published=1
            ORDER BY score DESC LIMIT ?""").use { s ->
            s.setInt(1, limit.coerceAtLeast(1))
            s.executeQuery().use { result -> buildList {
                fun nullableMetric(index: Int): Double = result.getDouble(index).let { if (result.wasNull()) Double.NaN else it }
                while (result.next()) {
                    val evaluatedAt = result.getLong(12)
                    val signalEpoch = result.getLong(13)
                    add(ScanResult(
                        symbol = result.getString(1), price = result.getDouble(2), anomalyScore = result.getDouble(4),
                        priceAnomaly = nullableMetric(5), rangeAnomaly = nullableMetric(6), volumeAnomaly = nullableMetric(7),
                        relativeVolume = nullableMetric(8), candleBodyRatio = Double.NaN,
                        windowChangePercent = result.getDouble(9), windowVolume = 0.0, sessionVolume = 0.0,
                        sessionTurnover = result.getDouble(10),
                        signalAgeMinutes = ((Instant.now().epochSecond - signalEpoch) / 60L).toInt().coerceAtLeast(1),
                        signalSource = result.getString(11), updatedAtMillis = evaluatedAt * 1_000,
                        dataStatus = "SAVED SNAPSHOT", signalWindowLabel = "10m saved",
                        signalPrice = result.getDouble(3), signalEpochMillis = signalEpoch * 1_000
                    ))
                }
            } }
        }
    }

    fun applyRetention(nowEpoch: Long = Instant.now().epochSecond) = locked {
        connection.prepareStatement("DELETE FROM minute_bars WHERE minute_epoch < ?").use { it.setLong(1, nowEpoch - RAW_RETENTION_DAYS * 86_400L); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM aggregate_bars WHERE bucket_epoch < ?").use { it.setLong(1, nowEpoch - AGGREGATE_RETENTION_DAYS * 86_400L); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM scan_runs WHERE started_at < ?").use { it.setLong(1, nowEpoch - SCAN_RETENTION_DAYS * 86_400L); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM data_quality WHERE observed_at < ?").use { it.setLong(1, nowEpoch - SCAN_RETENTION_DAYS * 86_400L); it.executeUpdate() }
    }

    fun stats(): AnalyticsStats = locked {
        connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT
                (SELECT COUNT(*) FROM instrument_metadata),
                (SELECT COUNT(*) FROM aggregate_bars),
                (SELECT COUNT(*) FROM scan_runs),
                (SELECT COUNT(*) FROM scan_candidates),
                (SELECT COUNT(*) FROM baseline_stats),
                (SELECT COUNT(*) FROM signal_outcomes),
                (SELECT COUNT(*) FROM broker_transactions),
                (SELECT COUNT(*) FROM broker_transactions WHERE linked_run_id IS NOT NULL)""").use { result ->
                check(result.next())
                AnalyticsStats(result.getLong(1), result.getLong(2), result.getLong(3),
                    result.getLong(4), result.getLong(5), result.getLong(6), result.getLong(7), result.getLong(8))
            }
        }
    }

    fun importScalableTransactions(path: Path): BrokerImportResult {
        val parsed = ScalableCsvImporter.parse(path)
        return locked { transaction { brokerTransactions.import(parsed) } }
    }

    fun loadBrokerTrades(symbol: String, companyName: String): List<BrokerTrade> =
        locked { brokerTransactions.loadTrades(symbol, companyName) }

    override fun close() = lock.withLock {
        runCatching { connection.createStatement().use { it.execute("PRAGMA optimize") } }
        connection.close()
    }

    private fun migrate() = locked {
        connection.createStatement().use { it.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)") }
        MIGRATIONS.forEach { (version, statements) ->
            val applied = connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version=?").use { s ->
                s.setInt(1, version); s.executeQuery().use { it.next() }
            }
            if (!applied) {
                connection.autoCommit = false
                try {
                    connection.createStatement().use { s -> statements.forEach(s::executeUpdate) }
                    connection.prepareStatement("INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)").use { s ->
                        s.setInt(1, version); s.setLong(2, Instant.now().epochSecond); s.executeUpdate()
                    }
                    connection.commit()
                } catch (error: Exception) {
                    connection.rollback(); throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
        log.info(LogTag.DB, "analytics schema ready version={}", MIGRATIONS.maxOf { it.first })
    }

    private fun upsertSessions(symbol: String, bars: List<MinuteBar>, source: String) {
        val zone = zoneFor(symbol)
        val groups = bars.groupBy { Instant.ofEpochSecond(it.minuteEpochSeconds).atZone(zone).toLocalDate().toString() }
        connection.prepareStatement("""INSERT INTO trading_sessions(symbol, session_date, open_epoch, close_epoch, bar_count, volume, turnover, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, session_date) DO UPDATE SET open_epoch=excluded.open_epoch,
            close_epoch=excluded.close_epoch, bar_count=excluded.bar_count, volume=excluded.volume, turnover=excluded.turnover, source=excluded.source""").use { s ->
            groups.forEach { (date, values) ->
                s.setString(1, symbol); s.setString(2, date); s.setLong(3, values.first().minuteEpochSeconds); s.setLong(4, values.last().minuteEpochSeconds)
                s.setInt(5, values.size); s.setDouble(6, values.sumOf(MinuteBar::volume)); s.setDouble(7, values.sumOf { it.close * it.volume })
                s.setString(8, source); s.addBatch()
            }; s.executeBatch()
        }
    }

    private fun upsertAggregates(symbol: String, bars: List<MinuteBar>) {
        connection.prepareStatement("""INSERT INTO aggregate_bars(symbol, resolution_minutes, bucket_epoch, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, resolution_minutes, bucket_epoch) DO UPDATE SET
            open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close, volume=excluded.volume""").use { s ->
            for (resolution in listOf(5, 15, 60)) {
                val seconds = resolution * 60L
                bars.groupBy { it.minuteEpochSeconds / seconds * seconds }.forEach { (bucket, values) ->
                    s.setString(1, symbol); s.setInt(2, resolution); s.setLong(3, bucket); s.setDouble(4, values.first().open)
                    s.setDouble(5, values.maxOf(MinuteBar::high)); s.setDouble(6, values.minOf(MinuteBar::low)); s.setDouble(7, values.last().close)
                    s.setDouble(8, values.sumOf(MinuteBar::volume)); s.addBatch()
                }
            }; s.executeBatch()
        }
    }

    private fun upsertBaselines(symbol: String, bars: List<MinuteBar>) {
        val zone = zoneFor(symbol)
        val features = bars.zipWithNext().mapNotNull { (a, b) ->
            if (a.close <= 0 || b.minuteEpochSeconds - a.minuteEpochSeconds !in 1..180) null else {
                val minute = Instant.ofEpochSecond(b.minuteEpochSeconds).atZone(zone).let { it.hour * 60 + it.minute }
                Triple(minute, (b.close / a.close - 1.0) * 100.0, ln1p(b.volume.coerceAtLeast(0.0)))
            }
        }.groupBy { it.first }
        connection.prepareStatement("""INSERT INTO baseline_stats(symbol, minute_of_session, sample_count, median_return, mad_return, median_log_volume, mad_log_volume, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, minute_of_session) DO UPDATE SET sample_count=excluded.sample_count,
            median_return=excluded.median_return, mad_return=excluded.mad_return, median_log_volume=excluded.median_log_volume,
            mad_log_volume=excluded.mad_log_volume, updated_at=excluded.updated_at""").use { s ->
            features.forEach { (minute, samples) ->
                val returns = samples.map { it.second }; val volumes = samples.map { it.third }
                val medianReturn = median(returns); val medianVolume = median(volumes)
                s.setString(1, symbol); s.setInt(2, minute); s.setInt(3, samples.size); s.setDouble(4, medianReturn)
                s.setDouble(5, median(returns.map { abs(it - medianReturn) })); s.setDouble(6, medianVolume)
                s.setDouble(7, median(volumes.map { abs(it - medianVolume) })); s.setLong(8, Instant.now().epochSecond); s.addBatch()
            }; s.executeBatch()
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted(); val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2 else sorted[middle]
    }

    private fun zoneFor(symbol: String) = MarketTimeZone.forSymbol(symbol)
    private fun <T> transaction(block: () -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }
    private inline fun <T> locked(block: () -> T): T = lock.withLock(block)

    private companion object {
        const val RAW_RETENTION_DAYS = 90
        const val AGGREGATE_RETENTION_DAYS = 730
        const val SCAN_RETENTION_DAYS = 180
        const val OUTCOME_MAX_LAG_MINUTES = 4
        val SCHEMA_V1 = listOf(
            """CREATE TABLE IF NOT EXISTS instrument_metadata(symbol TEXT PRIMARY KEY, name TEXT NOT NULL, exchange TEXT NOT NULL, currency TEXT NOT NULL, timezone TEXT NOT NULL, isin TEXT, wkn TEXT, aliases TEXT, tradable INTEGER NOT NULL, updated_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS corporate_actions(symbol TEXT NOT NULL, action_type TEXT NOT NULL, effective_epoch INTEGER NOT NULL, ratio REAL, amount REAL, currency TEXT, source TEXT NOT NULL, PRIMARY KEY(symbol, action_type, effective_epoch))""",
            """CREATE TABLE IF NOT EXISTS trading_sessions(symbol TEXT NOT NULL, session_date TEXT NOT NULL, open_epoch INTEGER NOT NULL, close_epoch INTEGER NOT NULL, bar_count INTEGER NOT NULL, volume REAL NOT NULL, turnover REAL NOT NULL, source TEXT NOT NULL, PRIMARY KEY(symbol, session_date))""",
            """CREATE TABLE IF NOT EXISTS market_calendar_rules(market TEXT PRIMARY KEY, timezone TEXT NOT NULL, weekdays TEXT NOT NULL, open_local TEXT NOT NULL, close_local TEXT NOT NULL, updated_at INTEGER NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS fx_rates(base_currency TEXT NOT NULL, quote_currency TEXT NOT NULL, rate_epoch INTEGER NOT NULL, rate REAL NOT NULL, source TEXT NOT NULL, PRIMARY KEY(base_currency, quote_currency, rate_epoch))""",
            """CREATE TABLE IF NOT EXISTS data_quality(id INTEGER PRIMARY KEY AUTOINCREMENT, symbol TEXT NOT NULL, source TEXT NOT NULL, observed_at INTEGER NOT NULL, latest_bar_epoch INTEGER, bar_count INTEGER NOT NULL, status TEXT NOT NULL, note TEXT)""",
            """CREATE INDEX IF NOT EXISTS idx_quality_symbol_time ON data_quality(symbol, observed_at DESC)""",
            """CREATE TABLE IF NOT EXISTS aggregate_bars(symbol TEXT NOT NULL, resolution_minutes INTEGER NOT NULL, bucket_epoch INTEGER NOT NULL, open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL, volume REAL NOT NULL, PRIMARY KEY(symbol, resolution_minutes, bucket_epoch))""",
            """CREATE INDEX IF NOT EXISTS idx_aggregate_symbol_time ON aggregate_bars(symbol, resolution_minutes, bucket_epoch)""",
            """CREATE TABLE IF NOT EXISTS baseline_stats(symbol TEXT NOT NULL, minute_of_session INTEGER NOT NULL, sample_count INTEGER NOT NULL, median_return REAL NOT NULL, mad_return REAL NOT NULL, median_log_volume REAL NOT NULL, mad_log_volume REAL NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(symbol, minute_of_session))""",
            """CREATE TABLE IF NOT EXISTS scan_runs(id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, completed_at INTEGER, region TEXT NOT NULL, requested_symbols INTEGER NOT NULL, evaluated_symbols INTEGER NOT NULL DEFAULT 0, accepted_symbols INTEGER NOT NULL DEFAULT 0, published_symbols INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0, interval_seconds INTEGER NOT NULL, status TEXT NOT NULL)""",
            """CREATE TABLE IF NOT EXISTS scan_candidates(run_id INTEGER NOT NULL REFERENCES scan_runs(id) ON DELETE CASCADE, symbol TEXT NOT NULL, evaluated_at INTEGER NOT NULL, accepted INTEGER NOT NULL, published INTEGER NOT NULL, rejection_reason TEXT, signal TEXT, score REAL, change_10m REAL, jump_z REAL, range_z REAL, volume_z REAL, rvol REAL, price REAL, turnover REAL, source TEXT NOT NULL, PRIMARY KEY(run_id, symbol))""",
            """CREATE INDEX IF NOT EXISTS idx_candidates_symbol_time ON scan_candidates(symbol, evaluated_at DESC)"""
        )
        val SCHEMA_V2 = listOf(
            """CREATE TABLE IF NOT EXISTS signal_outcomes(run_id INTEGER NOT NULL, symbol TEXT NOT NULL, horizon_minutes INTEGER NOT NULL, entry_price REAL NOT NULL, observed_price REAL NOT NULL, return_percent REAL NOT NULL, observed_at INTEGER NOT NULL, PRIMARY KEY(run_id, symbol, horizon_minutes), FOREIGN KEY(run_id, symbol) REFERENCES scan_candidates(run_id, symbol) ON DELETE CASCADE)""",
            """CREATE INDEX IF NOT EXISTS idx_outcomes_symbol_time ON signal_outcomes(symbol, observed_at DESC)""",
            """INSERT OR IGNORE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('US', 'America/New_York', '1,2,3,4,5', '09:30', '16:00', CAST(strftime('%s','now') AS INTEGER))""",
            """INSERT OR IGNORE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('EUROPE', 'Europe/Berlin', '1,2,3,4,5', '09:00', '17:30', CAST(strftime('%s','now') AS INTEGER))"""
        )
        val SCHEMA_V3 = listOf(
            "ALTER TABLE scan_candidates ADD COLUMN signal_epoch INTEGER",
            "ALTER TABLE scan_candidates ADD COLUMN entry_price REAL",
            "ALTER TABLE signal_outcomes ADD COLUMN elapsed_minutes REAL",
            "UPDATE scan_candidates SET signal_epoch=evaluated_at WHERE signal_epoch IS NULL",
            "UPDATE scan_candidates SET entry_price=price WHERE entry_price IS NULL",
            "UPDATE signal_outcomes SET elapsed_minutes=horizon_minutes WHERE elapsed_minutes IS NULL",
            "DELETE FROM market_calendar_rules WHERE market='EUROPE'",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('US', 'America/New_York', '1,2,3,4,5', '09:30', '16:00', CAST(strftime('%s','now') AS INTEGER))",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('XETRA', 'Europe/Berlin', '1,2,3,4,5', '09:00', '17:30', CAST(strftime('%s','now') AS INTEGER))",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('EURONEXT', 'Europe/Berlin', '1,2,3,4,5', '09:00', '17:30', CAST(strftime('%s','now') AS INTEGER))",
            "INSERT OR REPLACE INTO market_calendar_rules(market, timezone, weekdays, open_local, close_local, updated_at) VALUES ('HELSINKI', 'Europe/Helsinki', '1,2,3,4,5', '10:00', '18:30', CAST(strftime('%s','now') AS INTEGER))"
        )
        val SCHEMA_V4 = listOf(
            """CREATE TABLE IF NOT EXISTS broker_transactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, reference TEXT, fingerprint TEXT NOT NULL UNIQUE,
                occurred_at INTEGER NOT NULL, status TEXT NOT NULL, description TEXT NOT NULL, asset_type TEXT NOT NULL,
                transaction_type TEXT NOT NULL, isin TEXT, shares REAL NOT NULL, price REAL NOT NULL, amount REAL NOT NULL,
                fee REAL NOT NULL, tax REAL NOT NULL, currency TEXT NOT NULL, imported_at INTEGER NOT NULL,
                linked_run_id INTEGER, linked_symbol TEXT,
                FOREIGN KEY(linked_run_id, linked_symbol) REFERENCES scan_candidates(run_id, symbol) ON DELETE SET NULL)""",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_broker_source_reference ON broker_transactions(source, reference) WHERE reference IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_broker_isin_time ON broker_transactions(isin, occurred_at DESC)",
            "CREATE INDEX IF NOT EXISTS idx_broker_signal ON broker_transactions(linked_run_id, linked_symbol)"
        )
        val MIGRATIONS = listOf(1 to SCHEMA_V1, 2 to SCHEMA_V2, 3 to SCHEMA_V3, 4 to SCHEMA_V4)
        const val UPSERT_INSTRUMENT = """INSERT INTO instrument_metadata(symbol, name, exchange, currency, timezone, isin, wkn, aliases, tradable, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET name=excluded.name, exchange=excluded.exchange,
            currency=excluded.currency, timezone=excluded.timezone, isin=COALESCE(excluded.isin, instrument_metadata.isin),
            wkn=COALESCE(excluded.wkn, instrument_metadata.wkn), aliases=COALESCE(excluded.aliases, instrument_metadata.aliases),
            tradable=excluded.tradable, updated_at=excluded.updated_at"""
    }
}
