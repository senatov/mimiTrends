@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection
import kotlin.math.sqrt

internal class DownsideSafetyStore(private val connection: Connection) {
    fun calibration(european: Boolean, horizonMinutes: Int = 90): DownsideSafetyCalibration =
        connection.prepareStatement(SQL).use { statement ->
            statement.setDouble(1, MAX_ACCEPTABLE_DRAWDOWN_PERCENT)
            statement.setInt(2, horizonMinutes)
            statement.setInt(3, if (european) 1 else 0)
            statement.executeQuery().use { rows ->
                check(rows.next())
                val samples = rows.getInt("samples")
                val safe = rows.getInt("safe_samples")
                val days = rows.getInt("days")
                val probability = (safe + PRIOR_SAFE) / (samples + PRIOR_TOTAL)
                val confidence = (100.0 * minOf(
                    sqrt(samples / TARGET_SAMPLES.toDouble()),
                    days / TARGET_DAYS.toDouble()
                )).toInt().coerceIn(0, 100)
                DownsideSafetyCalibration(probability, confidence, samples, days,
                    horizonMinutes, MAX_ACCEPTABLE_DRAWDOWN_PERCENT)
            }
        }

    private companion object {
        const val MAX_ACCEPTABLE_DRAWDOWN_PERCENT = -0.80
        const val PRIOR_SAFE = 8.0
        const val PRIOR_TOTAL = 16.0
        const val TARGET_SAMPLES = 600
        const val TARGET_DAYS = 12
        const val SQL = """SELECT COUNT(*) samples,
            COALESCE(SUM(CASE WHEN o.minimum_return_percent>=? THEN 1 ELSE 0 END), 0) safe_samples,
            COUNT(DISTINCT date(s.observed_epoch, 'unixepoch')) days
            FROM research_samples s JOIN research_outcomes o ON o.sample_id=s.id
            WHERE o.horizon_minutes=? AND (instr(s.symbol, '.')>0)=?"""
    }
}
