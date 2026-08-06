package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import java.nio.file.Files
import kotlin.test.assertEquals

class SavedResultQuoteRefresherTest {
    @Test fun `hydrates a saved result from a recent stored provider quote`() {
        val repository = MarketRepository(Files.createTempDirectory("saved-result-quote").resolve("test.db"))
        repository.use {
            it.upsertProviderMinuteBar(ProviderMinuteBar(
                "TRADEGATE", "SAP.DE", "DE0007164600", "XGAT", "EUR",
                MinuteBar("SAP.DE", 9_960, 201.0, 201.0, 201.0, 201.0, 0.0, VolumeStatus.MISSING),
                9_970_000
            ))
            val saved = TestScanResult.create(symbol = "SAP.DE").copy(price = 190.0, updatedAtMillis = 1_000)

            val refreshed = SavedResultQuoteRefresher(it).refresh(listOf(saved), nowEpochSeconds = 10_000).single()

            assertEquals(201.0, refreshed.price)
            assertEquals(9_970_000, refreshed.updatedAtMillis)
            assertEquals("TRADEGATE", refreshed.dataStatus)
        }
    }
}
