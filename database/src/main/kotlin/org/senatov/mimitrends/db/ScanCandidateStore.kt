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
        val evaluatedAt = Instant.now().epochSecond
        val currency = result?.let {
            runCatching { DatabaseCurrencyNormalizer(connection).snapshot(symbol, it.price, evaluatedAt) }.getOrNull()
        }
        connection.prepareStatement(UPSERT_SQL).use { statement ->
            statement.setLong(1, runId)
            statement.setString(2, symbol.uppercase())
            statement.setLong(3, evaluatedAt)
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
            statement.setString(19, currency?.sourceCurrency ?: DatabaseCurrencyNormalizer.sourceCurrency(symbol))
            statement.setString(20, if (currency == null && result != null) "RATE_PENDING" else "INFERRED")
            metric(statement, 21, currency?.eurPrice)
            metric(statement, 22, result?.signalPrice?.let {
                currency?.let { snapshot -> it / snapshot.fxRate }
            })
            metric(statement, 23, currency?.fxRate)
            if (currency != null) statement.setLong(24, currency.fxRateEpoch) else statement.setNull(24, Types.INTEGER)
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
             jump_z, range_z, volume_z, rvol, price, entry_price, turnover, source, data_epoch,
             price_currency, currency_status, price_eur, entry_price_eur, fx_rate, fx_rate_epoch)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(run_id, symbol) DO UPDATE SET signal_epoch=excluded.signal_epoch,
            accepted=excluded.accepted, rejection_reason=excluded.rejection_reason,
            signal=excluded.signal, score=excluded.score, change_10m=excluded.change_10m, jump_z=excluded.jump_z,
            range_z=excluded.range_z, volume_z=excluded.volume_z, rvol=excluded.rvol, price=excluded.price,
            entry_price=excluded.entry_price, turnover=excluded.turnover, source=excluded.source,
            data_epoch=excluded.data_epoch, price_currency=excluded.price_currency,
            currency_status=excluded.currency_status, price_eur=excluded.price_eur,
            entry_price_eur=excluded.entry_price_eur, fx_rate=excluded.fx_rate,
            fx_rate_epoch=excluded.fx_rate_epoch"""
    }
}
