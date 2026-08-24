@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import java.sql.Connection
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln1p

internal class DerivedMarketAnalyticsStore(
    private val connection: Connection,
    private val duckAnalytics: DuckDbAnalyticsStore
) {
    fun upsert(symbol: String, bars: List<MinuteBar>, source: String) {
        upsertSessions(symbol, bars, source)
        upsertAggregates(symbol, bars)
        upsertBaselines(symbol, bars)
    }

    fun loadAggregatedBars(symbol: String, resolutionMinutes: Int, fromEpoch: Long): List<AggregatedBar> =
        connection.prepareStatement("""SELECT bucket_epoch, open, high, low, close, volume
            FROM aggregate_bars WHERE symbol=? AND resolution_minutes=? AND bucket_epoch>=?
            ORDER BY bucket_epoch""").use { statement ->
            statement.setString(1, symbol.uppercase())
            statement.setInt(2, resolutionMinutes)
            statement.setLong(3, fromEpoch)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(AggregatedBar(
                    symbol.uppercase(), resolutionMinutes, rows.getLong(1), rows.getDouble(2), rows.getDouble(3),
                    rows.getDouble(4), rows.getDouble(5), rows.getDouble(6)
                ))
            } }
        }

    private fun upsertSessions(symbol: String, bars: List<MinuteBar>, source: String) {
        val zone = MarketTimeZone.forSymbol(symbol)
        val groups = bars.groupBy { Instant.ofEpochSecond(it.minuteEpochSeconds).atZone(zone).toLocalDate().toString() }
        connection.prepareStatement("""INSERT INTO trading_sessions(symbol, session_date, open_epoch, close_epoch, bar_count, volume, turnover, source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, session_date) DO UPDATE SET open_epoch=excluded.open_epoch,
            close_epoch=excluded.close_epoch, bar_count=excluded.bar_count, volume=excluded.volume, turnover=excluded.turnover, source=excluded.source""").use { statement ->
            groups.forEach { (date, values) ->
                statement.setString(1, symbol); statement.setString(2, date)
                statement.setLong(3, values.first().minuteEpochSeconds); statement.setLong(4, values.last().minuteEpochSeconds)
                statement.setInt(5, values.size); statement.setDouble(6, values.sumOf(MinuteBar::volume))
                statement.setDouble(7, values.sumOf { it.close * it.volume }); statement.setString(8, source)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertAggregates(symbol: String, bars: List<MinuteBar>) {
        val aggregates = buildList {
            for (resolution in listOf(5, 15, 60)) {
                val seconds = resolution * 60L
                bars.groupBy { it.minuteEpochSeconds / seconds * seconds }.forEach { (bucket, values) ->
                    add(AggregatedBar(symbol, resolution, bucket, values.first().open, values.maxOf(MinuteBar::high),
                        values.minOf(MinuteBar::low), values.last().close, values.sumOf(MinuteBar::volume)))
                }
            }
        }
        connection.prepareStatement("""INSERT INTO aggregate_bars(symbol, resolution_minutes, bucket_epoch, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, resolution_minutes, bucket_epoch) DO UPDATE SET
            open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close, volume=excluded.volume""").use { statement ->
            aggregates.forEach { value ->
                statement.setString(1, value.symbol); statement.setInt(2, value.resolutionMinutes)
                statement.setLong(3, value.bucketEpochSeconds); statement.setDouble(4, value.open)
                statement.setDouble(5, value.high); statement.setDouble(6, value.low)
                statement.setDouble(7, value.close); statement.setDouble(8, value.volume); statement.addBatch()
            }
            statement.executeBatch()
        }
        duckAnalytics.upsertAggregates(aggregates)
    }

    private fun upsertBaselines(symbol: String, bars: List<MinuteBar>) {
        val zone = MarketTimeZone.forSymbol(symbol)
        val features = bars.zipWithNext().mapNotNull { (previous, current) ->
            if (previous.close <= 0 || current.minuteEpochSeconds - previous.minuteEpochSeconds !in 1..180) null
            else {
                val minute = Instant.ofEpochSecond(current.minuteEpochSeconds).atZone(zone).let { it.hour * 60 + it.minute }
                BaselineSample(minute, (current.close / previous.close - 1.0) * 100.0,
                    current.volume.takeIf { current.volumeStatus.isReliable && it > 0.0 }?.let(::ln1p))
            }
        }.groupBy(BaselineSample::minute)
        connection.prepareStatement("""INSERT INTO baseline_stats(symbol, minute_of_session, sample_count, median_return, mad_return, median_log_volume, mad_log_volume, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol, minute_of_session) DO UPDATE SET sample_count=excluded.sample_count,
            median_return=excluded.median_return, mad_return=excluded.mad_return, median_log_volume=excluded.median_log_volume,
            mad_log_volume=excluded.mad_log_volume, updated_at=excluded.updated_at""").use { statement ->
            features.forEach { (minute, samples) ->
                val returns = samples.map(BaselineSample::returnPercent)
                val volumes = samples.mapNotNull(BaselineSample::logVolume)
                val medianReturn = RobustStatistics.median(returns)
                val medianVolume = RobustStatistics.median(volumes)
                statement.setString(1, symbol); statement.setInt(2, minute); statement.setInt(3, samples.size)
                statement.setDouble(4, medianReturn)
                statement.setDouble(5, RobustStatistics.median(returns.map { abs(it - medianReturn) }))
                statement.setDouble(6, medianVolume)
                statement.setDouble(7, RobustStatistics.median(volumes.map { abs(it - medianVolume) }))
                statement.setLong(8, Instant.now().epochSecond); statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private data class BaselineSample(val minute: Int, val returnPercent: Double, val logVolume: Double?)
}
