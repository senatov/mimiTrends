package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoerseDeMarketDataClientTest {
    private val client = BoerseDeMarketDataClient()

    @Test
    fun `parses price currency and exchange timestamp`() {
        val quote = client.parseQuote("""
            <span itemprop="price" content="152.16">152,16</span>
            <span itemprop="priceCurrency">EUR</span>
            <span data-push-attribute="timestamp">12:35:07</span>
            <span data-push-attribute="date">06.08.26</span>
        """.trimIndent())

        assertEquals(152.16, quote.last)
        assertEquals("EUR", quote.currency)
        assertEquals(LocalDateTime.of(2026, 8, 6, 12, 35, 7).atZone(ZoneId.of("Europe/Berlin"))
            .toInstant().toEpochMilli(), quote.observedAtMillis)
    }

    @Test
    fun `rejects a page without a quote`() {
        assertFailsWith<ProviderDataUnavailableException> { client.parseQuote("<html></html>") }
    }
}
