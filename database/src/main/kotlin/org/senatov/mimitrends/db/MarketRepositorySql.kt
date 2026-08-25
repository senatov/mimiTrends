@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.sql.Connection
import java.sql.PreparedStatement

internal const val UPSERT_SQL = """INSERT INTO minute_bars(symbol, minute_epoch, open, high, low, close, volume, volume_status,
    source_currency, currency_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(symbol, minute_epoch) DO UPDATE SET open=excluded.open, high=excluded.high, low=excluded.low,
    close=excluded.close, volume=excluded.volume, volume_status=excluded.volume_status,
    source_currency=excluded.source_currency, currency_status=excluded.currency_status"""
internal const val UPSERT_PROFILE_SQL = """INSERT INTO company_profiles(symbol, name, exchange, logo_url, logo, updated_at)
    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET name=excluded.name, exchange=excluded.exchange,
    logo_url=excluded.logo_url, logo=excluded.logo, updated_at=excluded.updated_at"""
internal const val UPSERT_PROVIDER_INSTRUMENT_SQL = """INSERT INTO provider_instruments(
    provider, symbol, identifier, mic, currency, resolved_name, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(provider, symbol) DO UPDATE SET identifier=excluded.identifier, mic=excluded.mic,
    currency=excluded.currency, resolved_name=excluded.resolved_name, updated_at=excluded.updated_at"""
internal const val UPSERT_PROVIDER_BAR_SQL = """INSERT INTO provider_minute_bars(
    provider, symbol, identifier, mic, currency, minute_epoch, open, high, low, close, volume,
    volume_status, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(provider, symbol, minute_epoch) DO UPDATE SET high=MAX(provider_minute_bars.high, excluded.high),
    low=MIN(provider_minute_bars.low, excluded.low), close=excluded.close,
    volume=CASE WHEN excluded.volume_status='REPORTED' THEN excluded.volume ELSE provider_minute_bars.volume END,
    volume_status=CASE WHEN excluded.volume_status='REPORTED' THEN excluded.volume_status ELSE provider_minute_bars.volume_status END,
    observed_at=excluded.observed_at WHERE excluded.observed_at > provider_minute_bars.observed_at"""
internal const val UPSERT_PROVIDER_QUOTE_SQL = """INSERT INTO provider_quotes(provider, symbol, identifier, currency,
    last, bid, ask, bid_size, ask_size, session_volume, session_turnover, average_price, executions,
    session_high, session_low, previous_close, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(provider, symbol) DO UPDATE SET identifier=excluded.identifier, currency=excluded.currency,
    last=excluded.last, bid=excluded.bid, ask=excluded.ask, bid_size=excluded.bid_size, ask_size=excluded.ask_size,
    session_volume=excluded.session_volume, session_turnover=excluded.session_turnover,
    average_price=excluded.average_price, executions=excluded.executions, session_high=excluded.session_high,
    session_low=excluded.session_low, previous_close=excluded.previous_close, observed_at=excluded.observed_at
    WHERE excluded.observed_at > provider_quotes.observed_at"""

internal val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
private val EURO_SUFFIXES = listOf(".DE", ".F", ".PA", ".AS", ".MI", ".HE")
internal fun sourceCurrency(symbol: String): String =
    if (EURO_SUFFIXES.any(symbol.uppercase()::endsWith)) "EUR" else "USD"

internal object RetiredProviderCleaner {
    private val providers = listOf("BOERSE_DE", "BNP_PARIBAS", "TRADERFOX")

    fun clean(connection: Connection) {
        connection.prepareStatement("DELETE FROM provider_instruments WHERE provider=?").use(::delete)
        connection.prepareStatement("DELETE FROM provider_minute_bars WHERE provider=?").use(::delete)
        connection.prepareStatement("DELETE FROM provider_quotes WHERE provider=?").use(::delete)
    }

    private fun delete(statement: PreparedStatement) {
        providers.forEach { provider -> statement.setString(1, provider); statement.addBatch() }
        statement.executeBatch()
    }
}

internal object IncorrectProviderIdentityCleaner {
    fun clean(connection: Connection) {
        val hasMetadata = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='instrument_metadata'"
        ).use { statement -> statement.executeQuery().use { it.next() } }
        KNOWN_IDENTITIES.forEach { (symbol, correctIsin) ->
            if (hasMetadata) {
                connection.prepareStatement(
                    "UPDATE instrument_metadata SET isin=? WHERE symbol=? AND (isin IS NULL OR isin!=?)"
                ).use { statement ->
                    statement.setString(1, correctIsin); statement.setString(2, symbol)
                    statement.setString(3, correctIsin); statement.executeUpdate()
                }
            }
            listOf("provider_minute_bars", "provider_quotes", "provider_instruments").forEach { table ->
                connection.prepareStatement(
                    "DELETE FROM $table WHERE symbol=? AND identifier!=?"
                ).use { statement ->
                    statement.setString(1, symbol); statement.setString(2, correctIsin); statement.executeUpdate()
                }
            }
        }
    }

    private val KNOWN_IDENTITIES = mapOf(
        "NVO" to "US6701002056",
        "XOM" to "US30231G1022"
    )
}
