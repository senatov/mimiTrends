package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BnpParibasMarketDataClientTest {
    private val client = BnpParibasMarketDataClient()

    @Test
    fun `parses current indication without cookies`() {
        val quote = client.parseQuote("""
            {"result":{"price":151.82,"priceDate":"2026-08-06T12:44:24.136158",
            "isPriceToday":true,"currency":{"isoCode":"EUR"}}}
        """.trimIndent())

        assertEquals(151.82, quote.last)
        assertEquals("EUR", quote.currency)
        assertEquals(LocalDateTime.of(2026, 8, 6, 12, 44, 24, 136_158_000)
            .atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(), quote.observedAtMillis)
    }

    @Test
    fun `rejects a non-current indication`() {
        assertFailsWith<ProviderDataUnavailableException> {
            client.parseQuote("""{"result":{"price":151.82,"isPriceToday":false}}""")
        }
    }
}
