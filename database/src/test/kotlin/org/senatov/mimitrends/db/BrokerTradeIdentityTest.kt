package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ProviderInstrument
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrokerTradeIdentityTest {
    @Test
    fun `provider identity repairs stale metadata and prevents adjacent instrument trade leakage`() {
        val directory = Files.createTempDirectory("mimitrends-trade-identity")
        val database = directory.resolve("test.db")
        val csv = directory.resolve("transactions.csv")
        Files.writeString(
            csv, """date;time;status;reference;description;assetType;type;isin;shares;price;amount;fee;tax;currency
2026-08-24;15:12:00;Executed;BUY-BAS;BASF;Security;Buy;DE000BASF111;3;50,26;-150,78;0,00;0,00;EUR
2026-08-24;15:52:00;Executed;SELL-BAS;BASF;Security;Sell;DE000BASF111;3;50,03;150,09;0,00;0,00;EUR
"""
        )
        MarketRepository(database).use { market ->
            market.upsertProviderInstrument(
                ProviderInstrument(
                    "TRADEGATE", "HEN3.DE", "DE0006048432", "XGAT", "EUR", "Henkel", 1L
                )
            )
            market.upsertProviderInstrument(
                ProviderInstrument(
                    "TRADEGATE", "BAS.DE", "DE000BASF111", "XGAT", "EUR", "BASF", 1L
                )
            )
        }
        AnalyticsRepository(database).use { analytics ->
            analytics.upsertInstrument(
                InstrumentMetadata(
                    "HEN3.DE", "Henkel AG & Co. KGaA", "XETRA", "EUR", "Europe/Berlin", isin = "DE000BASF111"
                )
            )
            analytics.upsertInstrument(
                InstrumentMetadata(
                    "BAS.DE", "BASF SE", "XETRA", "EUR", "Europe/Berlin", isin = "DE000BASF111"
                )
            )
            analytics.importScalableTransactions(csv)

            assertTrue(analytics.loadBrokerTrades("HEN3.DE", "Henkel AG & Co. KGaA").isEmpty())
            assertEquals(1, analytics.loadBrokerTrades("BAS.DE", "BASF SE").size)
        }
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT isin FROM instrument_metadata WHERE symbol='HEN3.DE'"
            ).use { result ->
                assertTrue(result.next())
                assertEquals("DE0006048432", result.getString(1))
            }
        }
    }
}