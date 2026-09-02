package org.senatov.mimitrends.db

import java.sql.Connection

data class InstrumentCatalogEntry(val symbol: String, val name: String, val exchange: String)

@Suppress("SqlNoDataSourceInspection")
internal object InstrumentCatalogStore {
    fun search(connection: Connection, query: String, limit: Int): List<InstrumentCatalogEntry> {
        val term = "%${query.trim().lowercase()}%"
        return connection.prepareStatement(
            """WITH catalog(symbol, name, exchange) AS (
                SELECT symbol, name, exchange FROM company_profiles
                UNION SELECT symbol, resolved_name, mic FROM provider_instruments
                UNION SELECT symbol, symbol, '' FROM minute_bars
            )
            SELECT symbol, MAX(name), MAX(exchange) FROM catalog
            WHERE lower(symbol) LIKE ? OR lower(name) LIKE ?
            GROUP BY symbol
            ORDER BY CASE WHEN lower(symbol)=lower(?) THEN 0 WHEN lower(symbol) LIKE lower(?) THEN 1 ELSE 2 END,
                     symbol LIMIT ?"""
        ).use { statement ->
            statement.setString(1, term); statement.setString(2, term)
            statement.setString(3, query.trim()); statement.setString(4, "${query.trim()}%")
            statement.setInt(5, limit.coerceIn(1, 50))
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(
                        InstrumentCatalogEntry(
                            result.getString(1).trim().uppercase(), result.getString(2), result.getString(3)
                        )
                    )
                }
            }
        }
    }
}