@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.ScanResult
import java.sql.Connection
import java.sql.Types
import java.time.Instant

internal class ScanCandidateStore(
    private val connection: Connection,
    private val researchSamples: ResearchSampleStore
) {
    fun record(
        runId: Long,
        symbol: String,
        result: ScanResult?,
        rejectionReason: String?,
        source: String,
        researchFeatures: ResearchFeatures?
    ) {
        connection.prepareStatement(UPSERT_SQL).use { statement ->
            statement.setLong(1, runId)
            statement.setString(2, symbol.uppercase())
            statement.setLong(3, Instant.now().epochSecond)
            if (result != null) statement.setLong(4, result.signalEpochMillis / 1_000L)
            else statement.setNull(4, Types.INTEGER)
            statement.setInt(5, if (result != null) 1 else 0)
            statement.setString(6, rejectionReason)
            statement.setString(7, result?.signalSource)
            metric(statement, 8, result?.anomalyScore)
            metric(statement, 9, result?.windowChangePercent)
            metric(statement, 10, result?.priceAnomaly)
            metric(statement, 11, result?.rangeAnomaly)
            metric(statement, 12, result?.volumeAnomaly)
            metric(statement, 13, result?.relativeVolume)
            metric(statement, 14, result?.price)
            metric(statement, 15, result?.signalPrice)
            metric(statement, 16, result?.sessionTurnover)
            statement.setString(17, source)
            if (result != null) statement.setLong(18, result.updatedAtMillis / 1_000L)
            else statement.setNull(18, Types.INTEGER)
            statement.executeUpdate()
        }
        researchFeatures?.let { researchSamples.record(runId, symbol, result, it, source) }
    }

    private fun metric(statement: java.sql.PreparedStatement, index: Int, value: Double?) {
        if (value != null && value.isFinite()) statement.setDouble(index, value)
        else statement.setNull(index, Types.REAL)
    }

    private companion object {
        const val UPSERT_SQL = """INSERT INTO scan_candidates
            (run_id, symbol, evaluated_at, signal_epoch, accepted, published, rejection_reason, signal, score, change_10m,
             jump_z, range_z, volume_z, rvol, price, entry_price, turnover, source, data_epoch)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(run_id, symbol) DO UPDATE SET signal_epoch=excluded.signal_epoch,
            accepted=excluded.accepted, rejection_reason=excluded.rejection_reason,
            signal=excluded.signal, score=excluded.score, change_10m=excluded.change_10m, jump_z=excluded.jump_z,
            range_z=excluded.range_z, volume_z=excluded.volume_z, rvol=excluded.rvol, price=excluded.price,
            entry_price=excluded.entry_price, turnover=excluded.turnover, source=excluded.source,
            data_epoch=excluded.data_epoch"""
    }
}
