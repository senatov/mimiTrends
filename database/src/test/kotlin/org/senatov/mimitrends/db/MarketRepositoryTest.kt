package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.CompanyProfile
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals

class MarketRepositoryTest {
    @Test fun `stores and updates minute bars`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-db").resolve("test.db"))
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 102.0, 99.0, 101.0, 500.0))
        repository.upsertMinuteBar(MinuteBar("SAP.DE", 60, 100.0, 103.0, 99.0, 102.0, 750.0))
        val bars = repository.loadMinuteBars("SAP.DE", 0)
        assertEquals(1, bars.size); assertEquals(102.0, bars.single().close); assertEquals(750.0, bars.single().volume)
        assertEquals(listOf("SAP.DE"), repository.listSymbols())
        repository.close()
    }

    @Test fun `stores company profile and logo in a separate table`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-profiles").resolve("test.db"))
        val logo = byteArrayOf(1, 3, 5, 7)
        repository.upsertCompanyProfile(CompanyProfile("aapl", "Apple Inc", "NASDAQ", "https://logo", logo, 42))

        val stored = requireNotNull(repository.loadCompanyProfile("AAPL"))
        assertEquals("AAPL", stored.symbol)
        assertEquals("Apple Inc", stored.name)
        assertEquals("NASDAQ", stored.exchange)
        assertEquals("https://logo", stored.logoUrl)
        assertEquals(42, stored.updatedAtMillis)
        assertContentEquals(logo, stored.logoBytes)
        repository.close()
    }
}
