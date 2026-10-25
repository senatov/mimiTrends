@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.BrokerTrade
import org.senatov.mimitrends.model.ScanResult
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.time.Instant
import java.time.ZoneId

class AnalyticsRepository(
    private val database: EmbeddedDatabase = EmbeddedDatabase.open()
) : AutoCloseable {
    constructor(databasePath: Path) : this(EmbeddedDatabase.open(databasePath))

    private val log = LoggerFactory.getLogger(javaClass)
    private val connection: Connection = database.connection
    private val brokerTransactions: BrokerTransactionStore
    private val scanCandidates: ScanCandidateStore
    private val todayDetections: TodayDetectionStore

    init {
        migrate()
        recoverInterruptedScans()
        runCatching { DatabaseFileMaintenance.removeLegacyAnalyticsDatabase(database.path) }
            .onSuccess { removed ->
                if (removed != null) log.info(LogTag.DB, "removed legacy analytics database path={}", removed)
            }
            .onFailure { error -> log.warn(LogTag.DB, "unable to remove legacy analytics database", error) }
        brokerTransactions = BrokerTransactionStore(connection)
        scanCandidates = ScanCandidateStore(connection)
        todayDetections = TodayDetectionStore(connection)
    }

    fun upsertInstrument(value: InstrumentMetadata) = locked { upsertInstrumentInternal(value) }

    private fun upsertInstrumentInternal(value: InstrumentMetadata) {
        connection.prepareStatement(UPSERT_INSTRUMENT).use { s ->
            s.setString(1, value.symbol.uppercase()); s.setString(2, value.name); s.setString(3, value.exchange)
            s.setString(4, value.currency); s.setString(5, value.timezone); s.setString(6, value.isin)
            s.setString(7, value.wkn); s.setString(8, value.aliases); s.setInt(9, if (value.tradable) 1 else 0)
            s.setLong(10, value.updatedAtMillis); s.executeUpdate()
        }
    }

    fun upsertCorporateAction(value: CorporateAction) = locked { upsertCorporateActionInternal(value) }

    private fun upsertCorporateActionInternal(value: CorporateAction) {
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
            DatabaseCurrencyBackfill.run(connection)
        }
    }

    fun beginScan(region: String, requestedSymbols: Int, intervalSeconds: Long): Long = locked {
        connection.prepareStatement("""INSERT INTO scan_runs(started_at, region, requested_symbols, interval_seconds, status)
            VALUES (?, ?, ?, ?, 'RUNNING')""", Statement.RETURN_GENERATED_KEYS).use { s ->
            s.setLong(1, Instant.now().epochSecond); s.setString(2, region); s.setInt(3, requestedSymbols); s.setLong(4, intervalSeconds)
            s.executeUpdate(); s.generatedKeys.use { keys -> check(keys.next()); keys.getLong(1) }
        }
    }

    fun recordUniverseSelection(ranks: Map<String, Int>, dynamicSymbols: Collection<String>) = locked {
        val dynamic = dynamicSymbols.mapTo(hashSetOf(), String::uppercase)
        connection.prepareStatement("""INSERT OR REPLACE INTO universe_membership
            (selection_date, region, symbol, rank, source) VALUES (date('now'), ?, ?, ?, ?)""").use { statement ->
            ranks.forEach { (symbol, rank) ->
                statement.setString(1, if (symbol.contains('.')) "EUROPE" else "US")
                statement.setString(2, symbol.uppercase()); statement.setInt(3, rank)
                statement.setString(4, if (symbol.uppercase() in dynamic) "WALLSTREET_ONLINE" else "CONFIGURED_FALLBACK")
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
    fun recordScanCandidate(
        runId: Long,
        symbol: String,
        result: ScanResult?,
        rejectionReason: String?,
        source: String
    ) = locked { scanCandidates.record(runId, symbol, result, rejectionReason, source) }
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

    fun abortScan(runId: Long) = locked {
        connection.prepareStatement(
            """UPDATE scan_runs SET completed_at=?, status='ABORTED',
            evaluated_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=?),
            accepted_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=? AND accepted=1),
            published_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=? AND published=1)
            WHERE id=? AND status='RUNNING'""").use { statement ->
            statement.setLong(1, Instant.now().epochSecond)
            statement.setLong(2, runId); statement.setLong(3, runId); statement.setLong(4, runId)
            statement.setLong(5, runId)
            statement.executeUpdate()
        }
    }

    fun loadLatestPublishedResults(limit: Int): List<ScanResult> = locked {
        connection.prepareStatement(
            """SELECT c.symbol, c.price, c.entry_price, c.score, c.jump_z,
                c.range_z, c.volume_z, c.rvol, c.change_10m, c.turnover, c.signal, c.data_epoch,
                c.signal_epoch, c.source
            FROM scan_candidates c
            WHERE c.published=1 AND NOT EXISTS (
                SELECT 1 FROM scan_candidates newer
                WHERE newer.symbol=c.symbol AND newer.published=1 AND newer.run_id>c.run_id
            )
            ORDER BY c.run_id DESC, c.data_epoch DESC, c.score DESC LIMIT ?"""
        ).use { s ->
            s.setInt(1, limit.coerceAtLeast(1))
            s.executeQuery().use { result -> buildList {
                fun nullableMetric(index: Int): Double = result.getDouble(index).let { if (result.wasNull()) Double.NaN else it }
                while (result.next()) {
                    val dataEpoch = result.getLong(12)
                    val signalEpoch = result.getLong(13)
                    add(ScanResult(
                        symbol = result.getString(1), price = result.getDouble(2), anomalyScore = result.getDouble(4),
                        priceAnomaly = nullableMetric(5), rangeAnomaly = nullableMetric(6), volumeAnomaly = nullableMetric(7),
                        relativeVolume = nullableMetric(8), candleBodyRatio = Double.NaN,
                        windowChangePercent = result.getDouble(9), windowVolume = 0.0, sessionVolume = 0.0,
                        sessionTurnover = result.getDouble(10),
                        signalAgeMinutes = ((Instant.now().epochSecond - signalEpoch) / 60L).toInt().coerceAtLeast(1),
                        signalSource = result.getString(11), updatedAtMillis = dataEpoch * 1_000,
                        dataStatus = result.getString(14), signalWindowLabel = "10m saved",
                        signalPrice = result.getDouble(3), signalEpochMillis = signalEpoch * 1_000
                    ))
                }
            } }
        }
    }

    fun loadTodayDetections(
        now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()
    ): List<TodayDetection> = locked { todayDetections.load(now, zone) }

    fun applyRetention(nowEpoch: Long = Instant.now().epochSecond) = locked {
        val cutoff = nowEpoch - HISTORY_RETENTION_DAYS * 86_400L
        transaction {
            deleteBefore("minute_bars", "minute_epoch", cutoff)
            deleteBefore("provider_minute_bars", "minute_epoch", cutoff)
            deleteBefore("provider_quotes", "observed_at", cutoff)
            connection.prepareStatement(
                """UPDATE broker_transactions SET linked_run_id=NULL
                WHERE linked_run_id IN (SELECT id FROM scan_runs WHERE started_at < ?)"""
            ).use { statement ->
                statement.setLong(1, cutoff)
                statement.executeUpdate()
            }
            deleteBefore("scan_runs", "started_at", cutoff)
            deleteBefore("curve_candidates", "end_epoch", cutoff)
            deleteBefore("universe_membership", "selection_date", cutoff, date = true)
        }
        database.optimize()
        if (database.compactIfWorthwhile()) log.info(LogTag.DB, "SQLite storage compacted after history retention")
    }

    private fun deleteBefore(table: String, column: String, cutoff: Long, date: Boolean = false) {
        if (table == "curve_candidates") {
            val exists = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { statement ->
                statement.setString(1, table)
                statement.executeQuery().use { it.next() }
            }
            if (!exists) return
        }
        connection.prepareStatement("DELETE FROM $table WHERE $column < ?").use { statement ->
            if (date) statement.setString(1, Instant.ofEpochSecond(cutoff).atZone(ZoneId.of("UTC")).toLocalDate().toString())
            else statement.setLong(1, cutoff)
            statement.executeUpdate()
        }
    }

    fun importScalableTransactions(path: Path): BrokerImportResult {
        val parsed = ScalableCsvImporter.parseWithSummary(path)
        return locked {
            transaction {
                brokerTransactions.import(
                    parsed.transactions, parsed.rejected, parsed.duplicatesInFile
                )
            }
        }
    }

    fun loadBrokerTrades(symbol: String, companyName: String): List<BrokerTrade> =
        locked { brokerTransactions.loadTrades(symbol, companyName) }

    fun quickCheck(): String = database.quickCheck()

    fun databaseStats(): EmbeddedDatabaseStats = database.stats()

    fun backupIfDue(): Path? = database.backupIfDue()

    fun performShutdownMaintenance() = applyRetention().also {
        log.info(LogTag.DB, "database shutdown cleanup completed {}", DatabaseFileMaintenance.clean(database.path))
    }

    override fun close() {
        database.close()
    }
    private fun migrate() = locked {
        connection.createStatement().use { it.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)") }
        AnalyticsMigrations.values.forEach { (version, statements) ->
            val applied = connection.prepareStatement("SELECT 1 FROM schema_migrations WHERE version=?").use { s ->
                s.setInt(1, version); s.executeQuery().use { it.next() }
            }
            if (!applied) {
                if (version == OBSOLETE_TABLE_CLEANUP_MIGRATION) database.backupForMigration("obsolete-table-cleanup")
                connection.autoCommit = false
                try {
                    connection.createStatement().use { s -> statements.forEach(s::executeUpdate) }
                    if (version == CURRENCY_BACKFILL_MIGRATION) DatabaseCurrencyBackfill.runLegacyMigration(connection)
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
        log.info(LogTag.DB, "analytics schema ready version={}", AnalyticsMigrations.values.maxOf { it.first })
    }

    private fun recoverInterruptedScans(nowEpoch: Long = Instant.now().epochSecond) = locked {
        connection.prepareStatement(
            """UPDATE scan_runs SET completed_at=?, status='ABORTED',
            evaluated_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=scan_runs.id),
            accepted_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=scan_runs.id AND accepted=1),
            published_symbols=(SELECT COUNT(*) FROM scan_candidates WHERE run_id=scan_runs.id AND published=1)
            WHERE status='RUNNING' AND completed_at IS NULL""").use { statement ->
            statement.setLong(1, nowEpoch)
            val recovered = statement.executeUpdate()
            if (recovered > 0) log.warn(LogTag.DB, "recovered interrupted scan runs count={}", recovered)
        }
    }

    private fun <T> transaction(block: () -> T): T {
        check(connection.autoCommit) { "Nested analytics transactions are not supported" }
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        return try {
            block().also {
                connection.createStatement().use { statement -> statement.execute("COMMIT") }
            }
        } catch (error: Exception) {
            runCatching { connection.createStatement().use { statement -> statement.execute("ROLLBACK") } }
                .onFailure(error::addSuppressed)
            throw error
        }
    }
    private inline fun <T> locked(crossinline block: () -> T): T = database.locked { block() }

    private companion object {
        const val CURRENCY_BACKFILL_MIGRATION = 16
        const val OBSOLETE_TABLE_CLEANUP_MIGRATION = 19
        const val HISTORY_RETENTION_DAYS = 7L
        const val UPSERT_INSTRUMENT = """INSERT INTO instrument_metadata(symbol, name, exchange, currency, timezone, isin, wkn, aliases, tradable, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET name=excluded.name, exchange=excluded.exchange,
            currency=excluded.currency, timezone=excluded.timezone, isin=COALESCE(excluded.isin, instrument_metadata.isin),
            wkn=COALESCE(excluded.wkn, instrument_metadata.wkn), aliases=COALESCE(excluded.aliases, instrument_metadata.aliases),
            tradable=excluded.tradable, updated_at=excluded.updated_at"""
    }
}
