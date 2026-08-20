@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class DuckDbAnalyticsStore private constructor(
    val path: Path,
    private val connection: Connection
) : AutoCloseable {
    private val lock = ReentrantLock(true)
    private var lastResearchSyncMillis = 0L

    init {
        connection.createStatement().use { statement ->
            SCHEMA.forEach(statement::execute)
        }
    }

    fun synchronize(source: Connection) = lock.withLock {
        transaction {
            synchronizeAggregates(source)
            synchronizeResearchSamples(source)
            synchronizeResearchOutcomes(source)
        }
        lastResearchSyncMillis = System.currentTimeMillis()
    }

    fun synchronizeRecentResearch(source: Connection, nowMillis: Long = System.currentTimeMillis()) = lock.withLock {
        if (nowMillis - lastResearchSyncMillis < RESEARCH_SYNC_INTERVAL_MILLIS) return@withLock
        val latestSampleId = queryLong(LATEST_RESEARCH_SAMPLE_ID)
        val latestOutcomeEpoch = queryLong(LATEST_RESEARCH_OUTCOME_EPOCH)
        transaction {
            source.prepareStatement(SELECT_RECENT_RESEARCH_SAMPLES).use { statement ->
                statement.setLong(1, latestSampleId)
                copyRows(statement.executeQuery(), RESEARCH_SAMPLE_UPSERT, RESEARCH_SAMPLE_COLUMN_COUNT)
            }
            source.prepareStatement(SELECT_RECENT_RESEARCH_OUTCOMES).use { statement ->
                statement.setLong(1, (latestOutcomeEpoch - OUTCOME_RESYNC_SECONDS).coerceAtLeast(0L))
                copyRows(statement.executeQuery(), RESEARCH_OUTCOME_UPSERT, RESEARCH_OUTCOME_COLUMN_COUNT)
            }
        }
        lastResearchSyncMillis = nowMillis
    }

    fun loadAggregatedBars(symbol: String, resolutionMinutes: Int, fromEpoch: Long): List<AggregatedBar> =
        lock.withLock {
            connection.prepareStatement("""SELECT bucket_epoch, open, high, low, close, volume
                FROM aggregate_bars WHERE symbol=? AND resolution_minutes=? AND bucket_epoch>=?
                ORDER BY bucket_epoch""").use { statement ->
                statement.setString(1, symbol.uppercase())
                statement.setInt(2, resolutionMinutes)
                statement.setLong(3, fromEpoch)
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(AggregatedBar(symbol.uppercase(), resolutionMinutes,
                        rows.getLong(1), rows.getDouble(2), rows.getDouble(3), rows.getDouble(4),
                        rows.getDouble(5), rows.getDouble(6)))
                } }
            }
        }

    fun upsertAggregates(values: Collection<AggregatedBar>) = lock.withLock {
        if (values.isEmpty()) return@withLock
        connection.prepareStatement(UPSERT_AGGREGATE).use { statement ->
            values.forEach { value ->
                statement.setString(1, value.symbol.uppercase())
                statement.setInt(2, value.resolutionMinutes)
                statement.setLong(3, value.bucketEpochSeconds)
                statement.setDouble(4, value.open)
                statement.setDouble(5, value.high)
                statement.setDouble(6, value.low)
                statement.setDouble(7, value.close)
                statement.setDouble(8, value.volume)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    fun downsideSafetyCalibration(european: Boolean): DownsideSafetyCalibration =
        lock.withLock { DownsideSafetyStore(connection).calibration(european) }

    fun aggregateArchiveVerified(source: Connection): Boolean = lock.withLock {
        val sourceCount = source.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM aggregate_bars").use { rows -> rows.next(); rows.getLong(1) }
        }
        val archiveCount = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM aggregate_bars").use { rows -> rows.next(); rows.getLong(1) }
        }
        archiveCount >= sourceCount
    }

    fun applyRetention(nowEpoch: Long) = lock.withLock {
        connection.prepareStatement("DELETE FROM aggregate_bars WHERE bucket_epoch<?").use { statement ->
            statement.setLong(1, nowEpoch - DUCK_AGGREGATE_RETENTION_DAYS * 86_400L)
            statement.executeUpdate()
        }
        connection.createStatement().use { it.execute("CHECKPOINT") }
    }

    fun stats(): DuckDbAnalyticsStats = lock.withLock {
        DuckDbAnalyticsStats(
            Files.size(path),
            queryLong(AGGREGATE_BAR_COUNT),
            queryLong(RESEARCH_SAMPLE_COUNT),
            queryLong(RESEARCH_OUTCOME_COUNT)
        )
    }

    override fun close() = lock.withLock { connection.close() }

    private fun synchronizeAggregates(source: Connection) {
        source.createStatement().use { statement ->
            statement.fetchSize = BATCH_SIZE
            statement.executeQuery("""SELECT symbol, resolution_minutes, bucket_epoch, open, high, low, close, volume
                FROM aggregate_bars""").use { rows ->
                connection.prepareStatement(UPSERT_AGGREGATE).use { target ->
                    var pending = 0
                    while (rows.next()) {
                        for (index in 1..8) target.setObject(index, rows.getObject(index))
                        target.addBatch()
                        if (++pending % BATCH_SIZE == 0) target.executeBatch()
                    }
                    if (pending % BATCH_SIZE != 0) target.executeBatch()
                }
            }
        }
    }

    private fun synchronizeResearchSamples(source: Connection) {
        source.createStatement().use { statement ->
            statement.fetchSize = BATCH_SIZE
            copyRows(statement.executeQuery(SELECT_RESEARCH_SAMPLES), RESEARCH_SAMPLE_UPSERT,
                RESEARCH_SAMPLE_COLUMN_COUNT)
        }
    }

    private fun synchronizeResearchOutcomes(source: Connection) {
        source.createStatement().use { statement ->
            statement.fetchSize = BATCH_SIZE
            copyRows(statement.executeQuery(SELECT_RESEARCH_OUTCOMES), RESEARCH_OUTCOME_UPSERT,
                RESEARCH_OUTCOME_COLUMN_COUNT)
        }
    }

    private fun copyRows(rows: ResultSet, targetSql: String, columnCount: Int) {
        rows.use {
            connection.prepareStatement(targetSql).use { target ->
                var pending = 0
                while (rows.next()) {
                    copyRow(rows, target, columnCount)
                    target.addBatch()
                    if (++pending % BATCH_SIZE == 0) target.executeBatch()
                }
                if (pending % BATCH_SIZE != 0) target.executeBatch()
            }
        }
    }

    private fun copyRow(rows: ResultSet, target: PreparedStatement, columnCount: Int) {
        for (index in 1..columnCount) {
            val value = rows.getObject(index)
            if (value == null) target.setNull(index, Types.NULL) else target.setObject(index, value)
        }
    }

    private fun transaction(block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun queryLong(query: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery(query).use { rows -> rows.next(); rows.getLong(1) }
    }

    companion object {
        fun open(sqlitePath: Path): DuckDbAnalyticsStore {
            val path = sqlitePath.resolveSibling("mimitrends-analytics.duckdb")
            Files.createDirectories(path.parent)
            return DuckDbAnalyticsStore(path, DriverManager.getConnection("jdbc:duckdb:$path"))
        }

        private const val BATCH_SIZE = 2_000
        private const val RESEARCH_SYNC_INTERVAL_MILLIS = 60_000L
        private const val OUTCOME_RESYNC_SECONDS = 300L
        private const val DUCK_AGGREGATE_RETENTION_DAYS = 730L
        private const val RESEARCH_SAMPLE_COLUMN_COUNT = 33
        private const val RESEARCH_OUTCOME_COLUMN_COUNT = 8
        private const val AGGREGATE_BAR_COUNT = "SELECT COUNT(*) FROM aggregate_bars"
        private const val RESEARCH_SAMPLE_COUNT = "SELECT COUNT(*) FROM research_samples"
        private const val RESEARCH_OUTCOME_COUNT = "SELECT COUNT(*) FROM research_outcomes"
        private const val LATEST_RESEARCH_SAMPLE_ID = "SELECT COALESCE(MAX(id), 0) FROM research_samples"
        private const val LATEST_RESEARCH_OUTCOME_EPOCH =
            "SELECT COALESCE(MAX(observed_at), 0) FROM research_outcomes"
        private const val SELECT_RESEARCH_SAMPLES = """SELECT id, run_id, symbol, observed_epoch, entry_price,
            family, direction, accepted, published, source, score, jump_z, range_z, volume_z, rvol, return_1m,
            return_3m, return_5m, return_10m, return_30m, return_60m, range_10m, volatility_30m, vwap_distance,
            session_high_distance, session_low_distance, volume_ratio_10m, trend_efficiency_10m, entry_currency,
            currency_status, entry_price_eur, fx_rate, fx_rate_epoch FROM research_samples"""
        private const val SELECT_RECENT_RESEARCH_SAMPLES = """SELECT id, run_id, symbol, observed_epoch,
            entry_price, family, direction, accepted, published, source, score, jump_z, range_z, volume_z, rvol,
            return_1m, return_3m, return_5m, return_10m, return_30m, return_60m, range_10m, volatility_30m,
            vwap_distance, session_high_distance, session_low_distance, volume_ratio_10m, trend_efficiency_10m,
            entry_currency, currency_status, entry_price_eur, fx_rate, fx_rate_epoch FROM research_samples
            WHERE id>?"""
        private const val SELECT_RESEARCH_OUTCOMES = """SELECT sample_id, horizon_minutes, observed_price,
            return_percent, elapsed_minutes, maximum_return_percent, minimum_return_percent, observed_at
            FROM research_outcomes"""
        private const val SELECT_RECENT_RESEARCH_OUTCOMES = """SELECT sample_id, horizon_minutes, observed_price,
            return_percent, elapsed_minutes, maximum_return_percent, minimum_return_percent, observed_at
            FROM research_outcomes WHERE observed_at>=?"""
        private const val RESEARCH_SAMPLE_UPSERT = """INSERT OR REPLACE INTO research_samples VALUES
            (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
        private const val RESEARCH_OUTCOME_UPSERT = """INSERT OR REPLACE INTO research_outcomes VALUES
            (?, ?, ?, ?, ?, ?, ?, ?)"""
        private const val UPSERT_AGGREGATE = """INSERT INTO aggregate_bars VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(symbol, resolution_minutes, bucket_epoch) DO UPDATE SET open=excluded.open,
            high=excluded.high, low=excluded.low, close=excluded.close, volume=excluded.volume"""
        private val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS aggregate_bars(symbol VARCHAR NOT NULL, resolution_minutes INTEGER NOT NULL,
                bucket_epoch BIGINT NOT NULL, open DOUBLE NOT NULL, high DOUBLE NOT NULL, low DOUBLE NOT NULL,
                close DOUBLE NOT NULL, volume DOUBLE NOT NULL, PRIMARY KEY(symbol, resolution_minutes, bucket_epoch))""",
            """CREATE TABLE IF NOT EXISTS research_samples(id BIGINT PRIMARY KEY, run_id BIGINT NOT NULL,
                symbol VARCHAR NOT NULL, observed_epoch BIGINT NOT NULL, entry_price DOUBLE NOT NULL,
                family VARCHAR NOT NULL, direction INTEGER NOT NULL, accepted INTEGER NOT NULL,
                published INTEGER NOT NULL, source VARCHAR NOT NULL, score DOUBLE, jump_z DOUBLE, range_z DOUBLE,
                volume_z DOUBLE, rvol DOUBLE, return_1m DOUBLE, return_3m DOUBLE, return_5m DOUBLE,
                return_10m DOUBLE, return_30m DOUBLE, return_60m DOUBLE, range_10m DOUBLE,
                volatility_30m DOUBLE, vwap_distance DOUBLE, session_high_distance DOUBLE,
                session_low_distance DOUBLE, volume_ratio_10m DOUBLE, trend_efficiency_10m DOUBLE,
                entry_currency VARCHAR, currency_status VARCHAR, entry_price_eur DOUBLE, fx_rate DOUBLE,
                fx_rate_epoch BIGINT)""",
            """CREATE TABLE IF NOT EXISTS research_outcomes(sample_id BIGINT NOT NULL,
                horizon_minutes INTEGER NOT NULL, observed_price DOUBLE NOT NULL, return_percent DOUBLE NOT NULL,
                elapsed_minutes DOUBLE NOT NULL, maximum_return_percent DOUBLE, minimum_return_percent DOUBLE,
                observed_at BIGINT NOT NULL, PRIMARY KEY(sample_id, horizon_minutes))"""
        )
    }
}

data class DuckDbAnalyticsStats(
    val databaseBytes: Long,
    val aggregateBars: Long,
    val researchSamples: Long,
    val researchOutcomes: Long
)
