package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals

class EuronextMarketDataClientTest {
    private val client = EuronextMarketDataClient()

    @Test
    fun `decrypts the website CryptoJS envelope`() {
        val decrypted = client.decryptEnvelope(
            "YOBUDMmRjsilgZr7cJgcpE2iaiqT1NL1yK+ECtrIKAw=",
            "DF6675FA0EE3829C9D71124E37020847",
            "0011223344556677",
            "24ayqVo7yJma"
        )

        assertEquals("<span>ok</span>", decrypted)
    }

    @Test
    fun `parses delayed quote fields and last trade time`() {
        val quote = client.parseQuote("""
            <span id="header-instrument-currency">€</span>
            <span id="header-instrument-price">100.00</span>
            <div class="last-price-date-time">25/05/2026 - 16:34 &nbsp;CET</div>
            <span>Best Bid</span><span>103.12</span>
            <span>Best Ask</span><span>105.46</span>
        """.trimIndent())

        assertEquals(100.0, quote.last)
        assertEquals(103.12, quote.bid)
        assertEquals(105.46, quote.ask)
        assertEquals("EUR", quote.currency)
        assertEquals(
            LocalDateTime.of(2026, 5, 25, 16, 34).atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli(),
            quote.observedAtMillis
        )
    }
}
