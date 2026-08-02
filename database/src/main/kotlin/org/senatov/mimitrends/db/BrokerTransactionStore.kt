@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection
import java.time.Instant

internal class BrokerTransactionStore(private val connection: Connection) {
    fun import(transactions: List<BrokerTransaction>): BrokerImportResult {
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
        return BrokerImportResult(
            parsed = transactions.size,
            imported = imported,
            duplicates = transactions.size - imported,
            linkedToSignals = linkedTransactionCount()
        )
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

    private companion object {
        const val INSERT_TRANSACTION = """INSERT OR IGNORE INTO broker_transactions
            (source, reference, fingerprint, occurred_at, status, description, asset_type, transaction_type,
             isin, shares, price, amount, fee, tax, currency, imported_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""

        const val LINK_TO_SIGNALS = """UPDATE broker_transactions AS trade SET
            linked_symbol=(SELECT i.symbol FROM instrument_metadata i WHERE i.isin=trade.isin LIMIT 1),
            linked_run_id=(SELECT c.run_id FROM scan_candidates c JOIN instrument_metadata i ON i.symbol=c.symbol
                WHERE i.isin=trade.isin AND c.published=1 AND c.signal_epoch<=trade.occurred_at
                  AND c.signal_epoch>=trade.occurred_at-3600
                ORDER BY c.signal_epoch DESC LIMIT 1)
            WHERE trade.linked_run_id IS NULL AND trade.status='Executed'"""
    }
}
