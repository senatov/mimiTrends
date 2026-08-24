@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection
import java.time.Instant
import org.senatov.mimitrends.model.BrokerTrade

internal class BrokerTransactionStore(private val connection: Connection) {
    fun import(transactions: List<BrokerTransaction>): BrokerImportResult {
        removeCancelledTransactions()
        var imported = 0
        connection.prepareStatement(INSERT_TRANSACTION).use { statement ->
            transactions.forEach { value ->
                statement.setString(1, value.source)
                statement.setString(2, value.reference)
                statement.setString(3, value.fingerprint)
                statement.setLong(4, value.occurredAtEpochSeconds)
                statement.setString(5, value.status)
                statement.setString(6, value.description)
                statement.setString(7, value.assetType)
                statement.setString(8, value.type)
                statement.setString(9, value.isin)
                statement.setDouble(10, value.shares)
                statement.setDouble(11, value.price)
                statement.setDouble(12, value.amount)
                statement.setDouble(13, value.fee)
                statement.setDouble(14, value.tax)
                statement.setString(15, value.currency)
                statement.setLong(16, Instant.now().epochSecond)
                imported += statement.executeUpdate()
            }
        }
        linkExecutedTransactionsToSignals()
        val reconciliation = reconcileExecutions()
        return BrokerImportResult(
            parsed = transactions.size,
            imported = imported,
            duplicates = transactions.size - imported,
            linkedToSignals = linkedTransactionCount(),
            closedPositions = reconciliation.closedPositions,
            openPositions = reconciliation.openPositions,
            correctedOrder = reconciliation.correctedOrder,
            unmatchedSells = reconciliation.unmatchedSells
        )
    }

    private fun removeCancelledTransactions() {
        connection.createStatement().use { it.executeUpdate(DELETE_CANCELLED_TRANSACTIONS) }
    }

    private fun linkExecutedTransactionsToSignals() {
        connection.createStatement().use { it.executeUpdate(LINK_TO_SIGNALS) }
    }

    private fun linkedTransactionCount(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM broker_transactions WHERE linked_run_id IS NOT NULL").use {
            check(it.next())
            it.getInt(1)
        }
    }

    private fun reconcileExecutions(): ImportReconciliation {
        val executions = connection.prepareStatement(LOAD_EXECUTIONS).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) add(BrokerExecution(result.getLong(1), result.getLong(2), result.getString(3),
                    result.getString(4), result.getString(5), result.getDouble(6), result.getDouble(7),
                    result.getDouble(8), result.getDouble(9), result.getDouble(10), result.getString(11)))
            } }
        }
        val reconciliations = executions.groupBy { it.isin ?: "description:${it.description}" }
            .values.map { BrokerTradeMatcher.reconcile("", it) }
        return ImportReconciliation(
            closedPositions = reconciliations.sumOf { value -> value.trades.count { !it.isOpen } },
            openPositions = reconciliations.sumOf { value -> value.trades.count(BrokerTrade::isOpen) },
            correctedOrder = reconciliations.sumOf { it.correctedOrder },
            unmatchedSells = reconciliations.sumOf { it.unmatchedSells }
        )
    }

    fun loadTrades(symbol: String, companyName: String): List<BrokerTrade> {
        val normalizedSymbol = symbol.uppercase()
        val metadataIsin = connection.prepareStatement("SELECT isin FROM instrument_metadata WHERE symbol=?").use { statement ->
            statement.setString(1, normalizedSymbol)
            statement.executeQuery().use { result ->
                if (result.next()) result.getString(1)?.takeIf(::isValidIsin) else null
            }
        }
        // Provider mappings are instrument-specific and therefore take precedence over metadata
        // previously inferred from a broker description. This also repairs old false mappings.
        val authoritativeIsin = providerIsin(normalizedSymbol)
        val resolvedInstrumentIsin = authoritativeIsin ?: metadataIsin
        if (authoritativeIsin != null && authoritativeIsin != metadataIsin) {
            persistMapping(normalizedSymbol, authoritativeIsin, repairExisting = true)
        }
        val executions = connection.prepareStatement(LOAD_EXECUTIONS).use { statement ->
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val execution = BrokerExecution(result.getLong(1), result.getLong(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getDouble(6), result.getDouble(7),
                        result.getDouble(8), result.getDouble(9), result.getDouble(10), result.getString(11),
                        result.getString(12))
                    val linkedElsewhere = execution.linkedSymbol != null && execution.linkedSymbol != normalizedSymbol
                    val belongsToInstrument = if (resolvedInstrumentIsin != null) {
                        execution.isin == resolvedInstrumentIsin
                    } else {
                        BrokerTradeMatcher.matches(execution.description, companyName)
                    }
                    if (!linkedElsewhere && belongsToInstrument) add(execution)
                }
            } }
        }
        val inferredIsin = executions.mapNotNull(BrokerExecution::isin).distinct().singleOrNull()
        if (resolvedInstrumentIsin == null && inferredIsin != null) persistMapping(normalizedSymbol, inferredIsin)
        return BrokerTradeMatcher.pair(normalizedSymbol, executions)
    }

    private fun persistMapping(symbol: String, isin: String, repairExisting: Boolean = false) {
        val condition = if (repairExisting) "AND (isin IS NULL OR isin != ?)"
        else "AND (isin IS NULL OR length(isin) != 12)"
        connection.prepareStatement("UPDATE instrument_metadata SET isin=? WHERE symbol=? $condition").use {
            if (repairExisting) it.setString(3, isin)
            it.setString(1, isin); it.setString(2, symbol); it.executeUpdate()
        }
        connection.prepareStatement("""UPDATE broker_transactions SET linked_symbol=?
            WHERE isin=? AND linked_run_id IS NULL""").use {
            it.setString(1, symbol); it.setString(2, isin); it.executeUpdate()
        }
    }

    private fun isValidIsin(value: String): Boolean = value.matches(Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]"))

    private fun providerIsin(symbol: String): String? {
        val tableExists = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='provider_instruments'"
        ).use { statement -> statement.executeQuery().use { it.next() } }
        if (!tableExists) return null
        return connection.prepareStatement(
            "SELECT identifier FROM provider_instruments WHERE symbol=? ORDER BY updated_at DESC"
        ).use { statement ->
            statement.setString(1, symbol)
            statement.executeQuery().use { rows ->
                buildSet {
                    while (rows.next()) rows.getString(1)?.takeIf(::isValidIsin)?.let(::add)
                }.singleOrNull()
            }
        }
    }

    private companion object {
        const val DELETE_CANCELLED_TRANSACTIONS = """DELETE FROM broker_transactions
            WHERE lower(trim(status)) IN ('cancelled', 'cancel')"""
        const val INSERT_TRANSACTION = """INSERT OR IGNORE INTO broker_transactions
            (source, reference, fingerprint, occurred_at, status, description, asset_type, transaction_type,
             isin, shares, price, amount, fee, tax, currency, imported_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

        const val LINK_TO_SIGNALS = """UPDATE broker_transactions AS trade SET
            (linked_run_id, linked_symbol)=(SELECT c.run_id, c.symbol
                FROM scan_candidates c JOIN instrument_metadata i ON i.symbol=c.symbol
                WHERE i.isin=trade.isin AND c.published=1 AND c.signal_epoch<=trade.occurred_at
                  AND c.signal_epoch>=trade.occurred_at-3600
                ORDER BY c.signal_epoch DESC LIMIT 1)
            WHERE trade.linked_run_id IS NULL AND trade.status='Executed'"""
        const val LOAD_EXECUTIONS = """
            SELECT id, occurred_at, description, transaction_type, isin, shares, price, amount,
                fee, tax, currency, linked_symbol
            FROM broker_transactions
            WHERE lower(trim(status))='executed' AND lower(trim(transaction_type)) IN ('buy','sell')
            ORDER BY occurred_at, id
        """
    }

    private data class ImportReconciliation(
        val closedPositions: Int,
        val openPositions: Int,
        val correctedOrder: Int,
        val unmatchedSells: Int
    )
}