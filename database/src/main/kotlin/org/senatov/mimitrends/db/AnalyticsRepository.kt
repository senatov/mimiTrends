@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.isValidMinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.BrokerTrade
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln1p

class AnalyticsRepository(
    private val database: EmbeddedDatabase = EmbeddedDatabase.open()
) : AutoCloseable {
    constructor(databasePath: Path) : this(EmbeddedDatabase.open(databasePath))

    private val log = LoggerFactory.getLogger(javaClass)
    private val connection: Connection = database.connection
    private val brokerTransactions: BrokerTransactionStore
    private val signalCalibration: SignalCalibrationStore
    private val signalOutcomes: SignalOutcomeStore

    init {
        migrate()
        brokerTransactions = BrokerTransactionStore(connection)
        signalCalibration = SignalCalibrationStore(connection)
        signalOutcomes = SignalOutcomeStore(connection)
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
        source: String,
        status: String,
        bars: List<MinuteBar>
    ) = locked {
        val clean = bars.filter(MinuteBar::isValidMinuteBar).sortedBy(MinuteBar::minuteEpochSeconds)
        transaction {
            metadata?.let(::upsertInstrumentInternal)
            corporateActions.forEach(::upsertCorporateActionInternal)
            recordDataQualityInternal(symbol, source, status, clean.lastOrNull()?.minuteEpochSeconds, clean.size, null)
            if (clean.isNotEmpty()) {
                upsertSessions(symbol.uppercase(), clean, source)
                upsertAggregates(symbol.uppercase(), clean)
                upsertBaselines(symbol.uppercase(), clean)
                clean.takeLast(OUTCOME_TRACKING_BARS).forEach {
                    signalOutcomes.record(symbol, it.close, it.high, it.low, it.minuteEpochSeconds)
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

    fun recordSignalOutcomes(symbol: String, currentPrice: Double, observedEpoch: Long,
        highPrice: Double = currentPrice, lowPrice: Double = currentPrice
    ) = locked { transaction { signalOutcomes.record(symbol, currentPrice, highPrice, lowPrice, observedEpoch) } }

    fun recordSignalOutcomes(symbol: String, bars: List<MinuteBar>) = locked { transaction {
        bars.takeLast(OUTCOME_TRACKING_BARS).forEach {
            signalOutcomes.record(symbol, it.close, it.high, it.low, it.minuteEpochSeconds)
        }
    } }

    fun withCalibration(result: ScanResult): ScanResult = locked { signalCalibration.enrich(result) }

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

    fun quickCheck(): String = database.quickCheck()

    fun databaseStats(): EmbeddedDatabaseStats = database.stats()

    fun backupIfDue(): Path? = database.backupIfDue()

    override fun close() = database.close()

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
        log.info(LogTag.DB, "analytics schema ready version={}", AnalyticsMigrations.values.maxOf { it.first })
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
                BaselineSample(minute, (b.close / a.close - 1.0) * 100.0,
                    b.volume.takeIf { b.volumeStatus.isReliable && it > 0.0 }?.let(::ln1p))
            }
        }.groupBy(BaselineSample::minute)
        connection.prepareStatement("""INSERT INTO baseline_stats(symbol, minute_of_session, sample_count, median_return, mad_return, median_log_volume, mad_log_volume, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, minute_of_session) DO UPDATE SET sample_count=excluded.sample_count,
            median_return=excluded.median_return, mad_return=excluded.mad_return, median_log_volume=excluded.median_log_volume,
            mad_log_volume=excluded.mad_log_volume, updated_at=excluded.updated_at""").use { s ->
            features.forEach { (minute, samples) ->
                val returns = samples.map(BaselineSample::returnPercent)
                val volumes = samples.mapNotNull(BaselineSample::logVolume)
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
    private data class BaselineSample(val minute: Int, val returnPercent: Double, val logVolume: Double?)
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
    private inline fun <T> locked(crossinline block: () -> T): T = database.locked { block() }

    private companion object {
        const val RAW_RETENTION_DAYS = 90
        const val AGGREGATE_RETENTION_DAYS = 730
        const val SCAN_RETENTION_DAYS = 180
        const val OUTCOME_TRACKING_BARS = 35
        const val UPSERT_INSTRUMENT = """INSERT INTO instrument_metadata(symbol, name, exchange, currency, timezone, isin, wkn, aliases, tradable, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET name=excluded.name, exchange=excluded.exchange,
            currency=excluded.currency, timezone=excluded.timezone, isin=COALESCE(excluded.isin, instrument_metadata.isin),
            wkn=COALESCE(excluded.wkn, instrument_metadata.wkn), aliases=COALESCE(excluded.aliases, instrument_metadata.aliases),
            tradable=excluded.tradable, updated_at=excluded.updated_at"""
    }
}
