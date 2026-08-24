@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.CompanyProfile

internal class CompanyProfileStore(private val database: EmbeddedDatabase) {
    fun load(symbol: String): CompanyProfile? = database.locked { connection ->
        connection.prepareStatement(
            "SELECT name, exchange, logo_url, logo, updated_at FROM company_profiles WHERE symbol = ?"
        ).use { statement ->
            val normalized = symbol.trim().uppercase()
            statement.setString(1, normalized)
            statement.executeQuery().use { result -> if (result.next()) result.profile(normalized) else null }
        }
    }

    fun loadAll(): Map<String, CompanyProfile> = database.locked { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT symbol, name, exchange, logo_url, logo, updated_at FROM company_profiles"
            ).use { result ->
                buildMap {
                    while (result.next()) {
                        val symbol = result.getString(1).trim().uppercase()
                        put(
                            symbol, CompanyProfile(
                                symbol, result.getString(2), result.getString(3),
                                result.getString(4), result.getBytes(5), result.getLong(6)
                            )
                        )
                    }
                }
            }
        }
    }

    fun upsert(profile: CompanyProfile) = database.locked { connection ->
        connection.prepareStatement(UPSERT_PROFILE_SQL).use { statement ->
            statement.setString(1, profile.symbol.trim().uppercase())
            statement.setString(2, profile.name)
            statement.setString(3, profile.exchange)
            statement.setString(4, profile.logoUrl)
            statement.setBytes(5, profile.logoBytes)
            statement.setLong(6, profile.updatedAtMillis)
            statement.executeUpdate()
        }
    }

    private fun java.sql.ResultSet.profile(symbol: String) = CompanyProfile(
        symbol, getString(1), getString(2), getString(3), getBytes(4), getLong(5)
    )
}