@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.CompanyDomain
import org.senatov.mimitrends.model.CompanyDomainSource
import java.sql.Connection

internal class CompanyDomainStore(private val database: EmbeddedDatabase) {
    fun load(symbol: String): CompanyDomain? = database.locked { connection ->
        connection.prepareStatement(
            """SELECT domain, source, confidence, verified_at, last_success_at, failure_count, updated_at
               FROM company_domains WHERE symbol=?"""
        ).use { statement ->
            val normalized = symbol.trim().uppercase()
            statement.setString(1, normalized)
            statement.executeQuery().use { result ->
                if (!result.next()) null else CompanyDomain(
                    symbol = normalized,
                    domain = result.getString(1),
                    source = runCatching { CompanyDomainSource.valueOf(result.getString(2)) }
                        .getOrDefault(CompanyDomainSource.SEARCH),
                    confidence = result.getDouble(3),
                    verifiedAtMillis = result.nullableLong(4),
                    lastSuccessAtMillis = result.nullableLong(5),
                    failureCount = result.getInt(6),
                    updatedAtMillis = result.getLong(7)
                )
            }
        }
    }

    fun upsert(value: CompanyDomain) = database.locked { connection ->
        connection.prepareStatement(UPSERT_SQL).use { statement ->
            statement.setString(1, value.symbol.trim().uppercase())
            statement.setString(2, normalizeDomain(value.domain))
            statement.setString(3, value.source.name)
            statement.setDouble(4, value.confidence.coerceIn(0.0, 1.0))
            statement.setObject(5, value.verifiedAtMillis)
            statement.setObject(6, value.lastSuccessAtMillis)
            statement.setInt(7, value.failureCount.coerceAtLeast(0))
            statement.setLong(8, value.updatedAtMillis)
            statement.executeUpdate()
        }
    }

    companion object {
        fun migrate(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """CREATE TABLE IF NOT EXISTS company_domains (
                        symbol TEXT PRIMARY KEY, domain TEXT NOT NULL, source TEXT NOT NULL,
                        confidence REAL NOT NULL, verified_at INTEGER, last_success_at INTEGER,
                        failure_count INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
                    )"""
                )
            }
            connection.prepareStatement(
                """INSERT INTO company_domains(symbol, domain, source, confidence, updated_at)
                   VALUES (?, ?, 'SEED', 0.98, 0) ON CONFLICT(symbol) DO NOTHING"""
            ).use { statement ->
                SEEDS.forEach { (symbol, domain) ->
                    statement.setString(1, symbol); statement.setString(2, domain); statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        private fun normalizeDomain(value: String): String {
            val domain = value.trim().lowercase().removePrefix("https://").removePrefix("http://")
                .substringBefore('/').removePrefix("www.")
            require(DOMAIN.matches(domain)) { "Invalid company domain" }
            return domain
        }

        private fun java.sql.ResultSet.nullableLong(index: Int): Long? =
            getLong(index).let { value -> value.takeUnless { wasNull() } }

        private val DOMAIN = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")
        private val SEEDS = mapOf(
            "AAPL" to "apple.com", "MSFT" to "microsoft.com", "NVDA" to "nvidia.com",
            "AMZN" to "amazon.com", "META" to "meta.com", "GOOGL" to "google.com", "GOOG" to "google.com",
            "TSLA" to "tesla.com", "AVGO" to "broadcom.com", "JPM" to "jpmorganchase.com",
            "V" to "visa.com", "MA" to "mastercard.com", "LLY" to "lilly.com", "WMT" to "walmart.com",
            "ORCL" to "oracle.com", "NFLX" to "netflix.com", "AMD" to "amd.com", "COST" to "costco.com",
            "HD" to "homedepot.com", "BAC" to "bankofamerica.com", "XOM" to "exxonmobil.com",
            "CVX" to "chevron.com", "CRM" to "salesforce.com", "KO" to "coca-colacompany.com",
            "PEP" to "pepsico.com", "DIS" to "thewaltdisneycompany.com", "SAP.DE" to "sap.com",
            "SIE.DE" to "siemens.com", "ALV.DE" to "allianz.com", "DTE.DE" to "telekom.com",
            "BMW.DE" to "bmwgroup.com", "MBG.DE" to "group.mercedes-benz.com", "BAS.DE" to "basf.com",
            "RWE.DE" to "rwe.com", "DBK.DE" to "db.com", "DHL.DE" to "dhl.com", "ASML.AS" to "asml.com",
            "INGA.AS" to "ing.com", "AD.AS" to "aholddelhaize.com", "UNA.AS" to "unilever.com",
            "PHIA.AS" to "philips.com", "MC.PA" to "lvmh.com", "OR.PA" to "loreal.com",
            "TTE.PA" to "totalenergies.com", "AIR.PA" to "airbus.com", "BNP.PA" to "group.bnpparibas",
            "SAN.PA" to "sanofi.com", "SU.PA" to "se.com", "ENEL.MI" to "enel.com",
            "ISP.MI" to "group.intesasanpaolo.com", "STLAM.MI" to "stellantis.com"
        )

        private const val UPSERT_SQL = """INSERT INTO company_domains(
            symbol, domain, source, confidence, verified_at, last_success_at, failure_count, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(symbol) DO UPDATE SET
            domain=excluded.domain, source=excluded.source, confidence=excluded.confidence,
            verified_at=excluded.verified_at, last_success_at=excluded.last_success_at,
            failure_count=excluded.failure_count, updated_at=excluded.updated_at"""
    }
}
