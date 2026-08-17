@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection
import java.time.Instant
import java.time.ZoneId

internal class TodayDetectionStore(private val connection: Connection) {
    fun load(now: Instant, zone: ZoneId): List<TodayDetection> {
        val dayStart = now.atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        connection.prepareStatement("""SELECT symbol,
                COALESCE(MAX(signal), 'Signal'), MIN(evaluated_at), MAX(evaluated_at), COUNT(*),
                MAX(COALESCE(score, 0)), MAX(ABS(COALESCE(change_10m, 0)))
            FROM scan_candidates
            WHERE published=1 AND evaluated_at>=?
            GROUP BY symbol
            ORDER BY MAX(evaluated_at) DESC, MAX(COALESCE(score, 0)) DESC""").use { statement ->
            statement.setLong(1, dayStart)
            statement.executeQuery().use { result -> return buildList {
                while (result.next()) add(TodayDetection(
                    symbol = result.getString(1), signal = result.getString(2),
                    firstDetectedEpochSeconds = result.getLong(3), lastDetectedEpochSeconds = result.getLong(4),
                    publishedCycles = result.getInt(5), bestScore = result.getDouble(6),
                    largestMovePercent = result.getDouble(7)
                ))
            } }
        }
    }
}
