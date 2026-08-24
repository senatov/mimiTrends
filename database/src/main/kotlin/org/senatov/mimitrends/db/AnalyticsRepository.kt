@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketObservationQuality
import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.BrokerTrade
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.isValidMinuteBar
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
    private val signalOutcomes: SignalOutcomeStore
    private val researchSamples: ResearchSampleStore
    private val scanCandidates: ScanCandidateStore
    private val predictionAnalytics: PredictionAnalyticsStore
    private val duckAnalytics: DuckDbAnalyticsStore
    private val derivedAnalytics: DerivedMarketAnalyticsStore
    private val todayDetections: TodayDetectionStore

    init {
        migrate()
        recoverInterruptedScans()
        duckAnalytics = DuckDbAnalyticsStore.open(database.path)
        derivedAnalytics = DerivedMarketAnalyticsStore(connection, duckAnalytics)
        brokerTransactions = BrokerTransactionStore(connection)
        signalOutcomes = SignalOutcomeStore(connection)
        researchSamples = ResearchSampleStore(connection)
        scanCandidates = ScanCandidateStore(connection, researchSamples)
        predictionAnalytics = PredictionAnalyticsStore(connection)
        todayDetections = TodayDetectionStore(connection)
        duckAnalytics.stats().also { stats -> log.info(LogTag.DB,
            "DuckDB analytics opened size={}MiB aggregateBars={} researchSamples={} researchOutcomes={}",
            stats.databaseBytes / 1_048_576L, stats.aggregateBars, stats.researchSamples, stats.researchOutcomes) }
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

    fun recordDataQuality(symbol: String, source: String, status: String, latestEpoch: Long?, barCount: Int, note: String? = null) = locked {
        recordDataQualityInternal(symbol, source, status, latestEpoch, barCount, note)
    }

    private fun recordDataQualityInternal(
        symbol: String, source: String, status: String, latestEpoch: Long?, barCount: Int, note: String?
    ) {
        connection.prepareStatement("""INSERT INTO data_quality(symbol, source, observed_at, latest_bar_epoch, bar_count, status, note)
            VALUES (?, ?, ?, ?, ?, ?, ?)""").use { s ->
            s.setString(1, symbol.uppercase()); s.setString(2, source); s.setLong(3, Instant.now().epochSecond)
            s.setObject(4, latestEpoch); s.setInt(5, barCount); s.setString(6, status); s.setString(7, note); s.executeUpdate()
        }
    }

    fun recordMarketEvaluation(
        metadata: InstrumentMetadata?,
        corporateActions: Collection<CorporateAction>,
        symbol: String,
        historySource: String,
        status: String,
        bars: List<MinuteBar>,
        latestObservedEpoch: Long? = bars.lastOrNull()?.minuteEpochSeconds,
        observedSource: String = historySource,
        observationQuality: MarketObservationQuality = MarketObservationQuality.FULL_OHLCV
    ) = locked {
        val clean = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        transaction {
            metadata?.let(::upsertInstrumentInternal)
            corporateActions.forEach(::upsertCorporateActionInternal)
            recordDataQualityInternal(symbol, observedSource, status, latestObservedEpoch, clean.size,
                observationQuality.name)
            if (clean.isNotEmpty()) {
                derivedAnalytics.upsert(symbol.uppercase(), clean, historySource)
                clean.takeLast(OUTCOME_TRACKING_BARS).forEach {
                    signalOutcomes.record(symbol, it.close, it.high, it.low, it.minuteEpochSeconds)
                    researchSamples.recordOutcomes(symbol, it.close, it.high, it.low, it.minuteEpochSeconds)
                }
            }
        }
    }

    fun refreshDerived(symbol: String, bars: List<MinuteBar>, source: String) {
        if (bars.isEmpty()) return
        val normalized = symbol.uppercase()
        val clean = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        if (clean.isEmpty()) return
        locked {
            transaction {
                derivedAnalytics.upsert(normalized, clean, source)
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
        source: String,
        researchFeatures: ResearchFeatures? = null
    ) = locked { scanCandidates.record(runId, symbol, result, rejectionReason, source, researchFeatures) }

    fun recordSignalOutcomes(symbol: String, currentPrice: Double, observedEpoch: Long,
        highPrice: Double = currentPrice, lowPrice: Double = currentPrice
    ) = locked { transaction {
        signalOutcomes.record(symbol, currentPrice, highPrice, lowPrice, observedEpoch)
        researchSamples.recordOutcomes(symbol, currentPrice, highPrice, lowPrice, observedEpoch)
    } }

    fun recordSignalOutcomes(symbol: String, bars: List<MinuteBar>) = locked { transaction {
        bars.takeLast(OUTCOME_TRACKING_BARS).forEach {
            signalOutcomes.record(symbol, it.close, it.high, it.low, it.minuteEpochSeconds)
            researchSamples.recordOutcomes(symbol, it.close, it.high, it.low, it.minuteEpochSeconds)
        }
    } }

    fun withCalibration(result: ScanResult): ScanResult = locked { predictionAnalytics.enrich(result) }
    fun trainPredictiveModels(): List<PredictiveTrainingResult> = locked { transaction { predictionAnalytics.train() } }

    fun needsResearchBackfill(): Boolean = locked { researchSamples.needsHistoricalBackfill() }

    fun downsideSafetyCalibration(european: Boolean): DownsideSafetyCalibration =
        locked {
            if (duckAnalytics.stats().researchOutcomes > 0L) {
                duckAnalytics.downsideSafetyCalibration(european)
            } else {
                DownsideSafetyStore(connection).calibration(european)
            }
        }

    fun walkForwardResearchReport(horizonMinutes: Int = 10, frictionPercent: Double = 0.20): WalkForwardResearchReport =
        locked { predictionAnalytics.report(horizonMinutes, frictionPercent) }

    fun recordResearchBackfill(symbol: String, samples: Collection<ResearchBackfillSample>) = locked {
        transaction { samples.forEach { researchSamples.recordHistorical(symbol, it.result, it.features, it.outcomes) } }
    }
    fun completeScan(runId: Long, publishedSymbols: Collection<String>, failures: Int) = locked {
        transaction {
            researchSamples.markPublished(runId, publishedSymbols)
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
        connection.prepareStatement("""UPDATE scan_runs SET completed_at=?, status='ABORTED'
            WHERE id=? AND status='RUNNING'""").use { statement ->
            statement.setLong(1, Instant.now().epochSecond)
            statement.setLong(2, runId)
            statement.executeUpdate()
        }
    }

    fun loadAggregatedBars(symbol: String, resolutionMinutes: Int, fromEpoch: Long): List<AggregatedBar> = locked {
        duckAnalytics.loadAggregatedBars(symbol, resolutionMinutes, fromEpoch).ifEmpty {
            derivedAnalytics.loadAggregatedBars(symbol, resolutionMinutes, fromEpoch)
        }
    }

    fun loadLatestPublishedResults(limit: Int): List<ScanResult> = locked {
        connection.prepareStatement("""WITH latest_by_symbol AS (
                SELECT run_id, symbol, price, entry_price, score, jump_z, range_z, volume_z, rvol,
                    change_10m, turnover, signal, data_epoch, signal_epoch, source,
                    ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY run_id DESC) AS recency_rank
                FROM scan_candidates
                WHERE published=1
            )
            SELECT symbol, price, entry_price, score, jump_z, range_z, volume_z, rvol,
                change_10m, turnover, signal, data_epoch, signal_epoch, source
            FROM latest_by_symbol
            WHERE recency_rank=1
            ORDER BY run_id DESC, data_epoch DESC, score DESC LIMIT ?""").use { s ->
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
        connection.prepareStatement("DELETE FROM minute_bars WHERE minute_epoch < ?").use { it.setLong(1, nowEpoch - RAW_RETENTION_DAYS * 86_400L); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM provider_minute_bars WHERE minute_epoch < ?").use { it.setLong(1, nowEpoch - PROVIDER_RETENTION_DAYS * 86_400L); it.executeUpdate() }
        if (duckAnalytics.aggregateArchiveVerified(connection)) {
            database.backupForMigration("duckdb-aggregate-archive")
            connection.prepareStatement("DELETE FROM aggregate_bars WHERE bucket_epoch < ?").use {
                it.setLong(1, nowEpoch - SQLITE_AGGREGATE_TAIL_DAYS * 86_400L)
                it.executeUpdate()
            }
        }
        duckAnalytics.applyRetention(nowEpoch)
        connection.prepareStatement("DELETE FROM scan_runs WHERE started_at < ?").use { it.setLong(1, nowEpoch - SCAN_RETENTION_DAYS * 86_400L); it.executeUpdate() }
        connection.prepareStatement("DELETE FROM data_quality WHERE observed_at < ?").use { it.setLong(1, nowEpoch - DATA_QUALITY_RETENTION_DAYS * 86_400L); it.executeUpdate() }
        if (database.compactIfWorthwhile()) log.info(LogTag.DB, "SQLite storage compacted after DuckDB archival")
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

    fun quickCheck(): String = database.quickCheck()

    fun databaseStats(): EmbeddedDatabaseStats = database.stats()

    fun backupIfDue(): Path? = database.backupIfDue()

    override fun close() {
        duckAnalytics.close()
        database.close()
    }

    private fun migrate() = locked {
        connection.createStatement().use { it.executeUpdate("CREATE TABLE IF NOT EXISTS schema_migrations(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)") }
        AnalyticsMigrations.values.forEach { (version, statements) ->
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
        transaction { DatabaseCurrencyBackfill.run(connection) }
        log.info(LogTag.DB, "analytics schema ready version={}", AnalyticsMigrations.values.maxOf { it.first })
    }

    private fun recoverInterruptedScans(nowEpoch: Long = Instant.now().epochSecond) = locked {
        connection.prepareStatement("""UPDATE scan_runs SET completed_at=?, status='ABORTED'
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
        const val RAW_RETENTION_DAYS = 90
        const val PROVIDER_RETENTION_DAYS = 90
        const val SQLITE_AGGREGATE_TAIL_DAYS = 45L
        const val SCAN_RETENTION_DAYS = 180
        const val DATA_QUALITY_RETENTION_DAYS = 14
        const val OUTCOME_TRACKING_BARS = 35
        const val UPSERT_INSTRUMENT = """INSERT INTO instrument_metadata(symbol, name, exchange, currency, timezone, isin, wkn, aliases, tradable, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET name=excluded.name, exchange=excluded.exchange,
            currency=excluded.currency, timezone=excluded.timezone, isin=COALESCE(excluded.isin, instrument_metadata.isin),
            wkn=COALESCE(excluded.wkn, instrument_metadata.wkn), aliases=COALESCE(excluded.aliases, instrument_metadata.aliases),
            tradable=excluded.tradable, updated_at=excluded.updated_at"""
    }
}
