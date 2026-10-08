@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.CurveCandidate
import org.senatov.mimitrends.model.CurveDecision
import org.senatov.mimitrends.model.CurveDirection
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant

class CurveCandidateStore(private val database: EmbeddedDatabase = EmbeddedDatabase.open()) : AutoCloseable {
    constructor(path: Path) : this(EmbeddedDatabase.open(path))

    private val migration by lazy {
        database.locked { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """CREATE TABLE IF NOT EXISTS curve_candidates (
                            symbol TEXT NOT NULL, direction TEXT NOT NULL, anchor_epoch INTEGER NOT NULL,
                            end_epoch INTEGER NOT NULL, start_price REAL NOT NULL, end_price REAL NOT NULL,
                            change_percent REAL NOT NULL, duration_minutes INTEGER NOT NULL,
                            volatility_percent REAL NOT NULL, acceleration_percent_per_minute REAL NOT NULL,
                            path_efficiency REAL NOT NULL, reversal_percent REAL NOT NULL,
                            reported_volume_bars INTEGER NOT NULL, turnover REAL NOT NULL, bar_count INTEGER NOT NULL,
                            feature_version INTEGER NOT NULL DEFAULT 1, decision TEXT NOT NULL DEFAULT 'PENDING',
                            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, reviewed_at INTEGER,
                            PRIMARY KEY(symbol, direction, anchor_epoch)
                        )"""
                    )
                    statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_curve_candidates_decision_time ON curve_candidates(decision, end_epoch)"
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
    }

    fun observe(candidate: CurveCandidate): CurveDecision = database.locked { connection ->
        migration
        val now = Instant.now().epochSecond
        connection.prepareStatement(
            """INSERT INTO curve_candidates (
                symbol, direction, anchor_epoch, end_epoch, start_price, end_price, change_percent,
                duration_minutes, volatility_percent, acceleration_percent_per_minute, path_efficiency,
                reversal_percent, reported_volume_bars, turnover, bar_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(symbol, direction, anchor_epoch) DO UPDATE SET
                end_epoch=excluded.end_epoch, end_price=excluded.end_price,
                change_percent=excluded.change_percent, duration_minutes=excluded.duration_minutes,
                volatility_percent=excluded.volatility_percent,
                acceleration_percent_per_minute=excluded.acceleration_percent_per_minute,
                path_efficiency=excluded.path_efficiency, reversal_percent=excluded.reversal_percent,
                reported_volume_bars=excluded.reported_volume_bars, turnover=excluded.turnover,
                bar_count=excluded.bar_count, updated_at=excluded.updated_at
            WHERE excluded.end_epoch > curve_candidates.end_epoch"""
        ).use { statement ->
            statement.setString(1, candidate.symbol.uppercase())
            statement.setString(2, candidate.direction.name)
            statement.setLong(3, candidate.anchorEpochSeconds)
            statement.setLong(4, candidate.endEpochSeconds)
            statement.setDouble(5, candidate.startPrice)
            statement.setDouble(6, candidate.endPrice)
            statement.setDouble(7, candidate.changePercent)
            statement.setInt(8, candidate.durationMinutes)
            statement.setDouble(9, candidate.volatilityPercent)
            statement.setDouble(10, candidate.accelerationPercentPerMinute)
            statement.setDouble(11, candidate.pathEfficiency)
            statement.setDouble(12, candidate.reversalPercent)
            statement.setInt(13, candidate.reportedVolumeBars)
            statement.setDouble(14, candidate.turnover)
            statement.setInt(15, candidate.barCount)
            statement.setLong(16, now)
            statement.setLong(17, now)
            statement.executeUpdate()
        }
        val exact = decision(connection, candidate)
        if (exact == CurveDecision.PENDING && rejectedNearby(connection, candidate)) CurveDecision.REJECTED else exact
    }

    fun review(candidate: CurveCandidate, approved: Boolean): Boolean = database.locked { connection ->
        migration
        connection.prepareStatement(
            """UPDATE curve_candidates SET decision=?, reviewed_at=?, updated_at=?
               WHERE symbol=? AND direction=? AND anchor_epoch=? AND decision='PENDING'"""
        ).use { statement ->
            val now = Instant.now().epochSecond
            statement.setString(1, if (approved) CurveDecision.APPROVED.name else CurveDecision.REJECTED.name)
            statement.setLong(2, now)
            statement.setLong(3, now)
            statement.setString(4, candidate.symbol.uppercase())
            statement.setString(5, candidate.direction.name)
            statement.setLong(6, candidate.anchorEpochSeconds)
            statement.executeUpdate() == 1
        }
    }

    fun decision(candidate: CurveCandidate): CurveDecision = database.locked {
        migration
        decision(it, candidate)
    }

    fun loadReviewed(): List<Pair<CurveCandidate, CurveDecision>> = database.locked { connection ->
        migration
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """SELECT symbol, direction, anchor_epoch, end_epoch, start_price, end_price,
                    change_percent, duration_minutes, volatility_percent, acceleration_percent_per_minute,
                    path_efficiency, reversal_percent, reported_volume_bars, turnover, bar_count, decision
                    FROM curve_candidates WHERE decision IN ('APPROVED', 'REJECTED')
                    ORDER BY reviewed_at DESC LIMIT 3000"""
            ).use { rows ->
                buildList {
                    while (rows.next()) add(
                        CurveCandidate(
                            symbol = rows.getString(1), direction = CurveDirection.valueOf(rows.getString(2)),
                            anchorEpochSeconds = rows.getLong(3), endEpochSeconds = rows.getLong(4),
                            startPrice = rows.getDouble(5), endPrice = rows.getDouble(6),
                            changePercent = rows.getDouble(7), durationMinutes = rows.getInt(8),
                            volatilityPercent = rows.getDouble(9), accelerationPercentPerMinute = rows.getDouble(10),
                            pathEfficiency = rows.getDouble(11), reversalPercent = rows.getDouble(12),
                            reportedVolumeBars = rows.getInt(13), turnover = rows.getDouble(14),
                            barCount = rows.getInt(15)
                        ) to CurveDecision.valueOf(rows.getString(16))
                    )
                }
            }
        }
    }

    private fun decision(connection: Connection, candidate: CurveCandidate): CurveDecision =
        connection.prepareStatement(
            "SELECT decision FROM curve_candidates WHERE symbol=? AND direction=? AND anchor_epoch=?"
        ).use { statement ->
            statement.setString(1, candidate.symbol.uppercase())
            statement.setString(2, candidate.direction.name)
            statement.setLong(3, candidate.anchorEpochSeconds)
            statement.executeQuery().use { rows ->
                if (rows.next()) CurveDecision.valueOf(rows.getString(1)) else CurveDecision.PENDING
            }
        }

    private fun rejectedNearby(connection: Connection, candidate: CurveCandidate): Boolean =
        connection.prepareStatement(
            """SELECT 1 FROM curve_candidates WHERE symbol=? AND direction=? AND decision='REJECTED'
               AND anchor_epoch BETWEEN ? AND ? AND end_epoch>=? LIMIT 1"""
        ).use { statement ->
            statement.setString(1, candidate.symbol.uppercase())
            statement.setString(2, candidate.direction.name)
            statement.setLong(3, candidate.anchorEpochSeconds - REVIEW_COOLDOWN_SECONDS)
            statement.setLong(4, candidate.anchorEpochSeconds + REVIEW_COOLDOWN_SECONDS)
            statement.setLong(5, candidate.anchorEpochSeconds)
            statement.executeQuery().use { it.next() }
        }

    override fun close() = database.close()

    private companion object {
        const val REVIEW_COOLDOWN_SECONDS = 10 * 60L
    }
}