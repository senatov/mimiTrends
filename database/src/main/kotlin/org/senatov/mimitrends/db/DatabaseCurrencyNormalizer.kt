@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection
import org.senatov.mimitrends.model.InstrumentCurrency

internal data class EurPriceSnapshot(
    val sourceCurrency: String,
    val eurPrice: Double,
    val fxRate: Double,
    val fxRateEpoch: Long
)

internal class DatabaseCurrencyNormalizer(private val connection: Connection) {
    fun snapshot(symbol: String, price: Double, epochSeconds: Long): EurPriceSnapshot {
        val currency = sourceCurrency(symbol)
        if (currency == "EUR") return EurPriceSnapshot(currency, price, 1.0, epochSeconds)
        val rate = connection.prepareStatement("""SELECT rate, rate_epoch FROM fx_rates
            WHERE base_currency='EUR' AND quote_currency='USD' AND rate_epoch<=?
            ORDER BY rate_epoch DESC LIMIT 1""").use { statement ->
            statement.setLong(1, epochSeconds)
            statement.executeQuery().use { result ->
                if (result.next()) result.getDouble(1) to result.getLong(2) else null
            }
        } ?: error("EUR/USD rate unavailable for currency normalization")
        return EurPriceSnapshot(currency, price / rate.first, rate.first, rate.second)
    }

    companion object {
        fun sourceCurrency(symbol: String): String = InstrumentCurrency.infer(symbol)
    }
}

internal object DatabaseCurrencyBackfill {
    fun run(connection: Connection) {
        val normalizer = DatabaseCurrencyNormalizer(connection)
        backfillCandidates(connection, normalizer)
        backfillResearchSamples(connection, normalizer)
    }

    private fun backfillCandidates(connection: Connection, normalizer: DatabaseCurrencyNormalizer) {
        val rows = connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT run_id, symbol, evaluated_at, price, entry_price
                FROM scan_candidates WHERE price IS NOT NULL AND price_eur IS NULL""").use { result ->
                buildList {
                    while (result.next()) add(CandidateRow(result.getLong(1), result.getString(2), result.getLong(3),
                        result.getDouble(4), result.getDouble(5).takeUnless { result.wasNull() }))
                }
            }
        }
        connection.prepareStatement("""UPDATE scan_candidates SET price_currency=?, currency_status='INFERRED',
            price_eur=?, entry_price_eur=?, fx_rate=?, fx_rate_epoch=? WHERE run_id=? AND symbol=?""").use { statement ->
            rows.forEach { row ->
                val price = runCatching { normalizer.snapshot(row.symbol, row.price, row.epoch) }.getOrNull()
                    ?: return@forEach
                statement.setString(1, price.sourceCurrency); statement.setDouble(2, price.eurPrice)
                statement.setObject(3, row.entryPrice?.let { normalizer.snapshot(row.symbol, it, row.epoch).eurPrice })
                statement.setDouble(4, price.fxRate); statement.setLong(5, price.fxRateEpoch)
                statement.setLong(6, row.runId); statement.setString(7, row.symbol); statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate("""UPDATE scan_candidates SET
                price_currency=CASE WHEN symbol LIKE '%.DE' OR symbol LIKE '%.F' OR symbol LIKE '%.PA'
                    OR symbol LIKE '%.AS' OR symbol LIKE '%.MI' OR symbol LIKE '%.HE' THEN 'EUR' ELSE 'USD' END,
                currency_status='RATE_PENDING' WHERE price IS NOT NULL AND price_eur IS NULL""")
        }
    }

    private fun backfillResearchSamples(connection: Connection, normalizer: DatabaseCurrencyNormalizer) {
        val rows = connection.createStatement().use { statement ->
            statement.executeQuery("""SELECT id, symbol, observed_epoch, entry_price FROM research_samples
                WHERE entry_price_eur IS NULL""").use { result ->
                buildList {
                    while (result.next()) add(ResearchRow(result.getLong(1), result.getString(2),
                        result.getLong(3), result.getDouble(4)))
                }
            }
        }
        connection.prepareStatement("""UPDATE research_samples SET entry_currency=?, currency_status='INFERRED',
            entry_price_eur=?, fx_rate=?, fx_rate_epoch=? WHERE id=?""").use { statement ->
            rows.forEach { row ->
                val price = runCatching { normalizer.snapshot(row.symbol, row.price, row.epoch) }.getOrNull()
                    ?: return@forEach
                statement.setString(1, price.sourceCurrency); statement.setDouble(2, price.eurPrice)
                statement.setDouble(3, price.fxRate); statement.setLong(4, price.fxRateEpoch)
                statement.setLong(5, row.id); statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate("""UPDATE research_samples SET
                entry_currency=CASE WHEN symbol LIKE '%.DE' OR symbol LIKE '%.F' OR symbol LIKE '%.PA'
                    OR symbol LIKE '%.AS' OR symbol LIKE '%.MI' OR symbol LIKE '%.HE' THEN 'EUR' ELSE 'USD' END,
                currency_status='RATE_PENDING' WHERE entry_price_eur IS NULL""")
        }
    }

    private data class CandidateRow(val runId: Long, val symbol: String, val epoch: Long,
                                    val price: Double, val entryPrice: Double?)
    private data class ResearchRow(val id: Long, val symbol: String, val epoch: Long, val price: Double)
}
