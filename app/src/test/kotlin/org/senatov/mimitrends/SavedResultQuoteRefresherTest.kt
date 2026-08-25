package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import java.nio.file.Files
import kotlin.test.assertEquals

class SavedResultQuoteRefresherTest {
    @Test fun `hydrates a saved result from a recent stored provider quote`() {
        val repository = MarketRepository(Files.createTempDirectory("saved-result-quote").resolve("test.db"))
        repository.use {
            it.upsertProviderQuote(
                ProviderQuoteSnapshot(
                    "TRADEGATE", "SAP.DE", "DE0007164600", "EUR", 201.0, 200.9, 201.1,
                    null, null, null, null, null, null, null, null, null, 9_970_000
            ))
            val saved = TestScanResult.create(symbol = "SAP.DE").copy(price = 190.0, updatedAtMillis = 1_000)

            val refreshed = SavedResultQuoteRefresher(it).refresh(listOf(saved), nowEpochSeconds = 10_000).single()

            assertEquals(201.0, refreshed.price)
            assertEquals(200.9, refreshed.bidPrice)
            assertEquals(201.1, refreshed.askPrice)
            assertEquals(9_970_000, refreshed.updatedAtMillis)
            assertEquals("TRADEGATE", refreshed.dataStatus)
        }
    }
}