package org.senatov.mimitrends.marketdata

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDate
import java.time.ZoneId

class WallstreetOnlineMarketDataClientTest {
    private val client = WallstreetOnlineMarketDataClient()

    @Test
    fun `parses mover table row`() {
        val html = """
            <table><tr><td>1.</td><td><a href="/aktien/nemetschek-aktie">Nemetschek</a></td>
            <td><span data-push="2;ls;quotes;10767@21@27;t">61,50</span></td>
            <td class="drel right"><span><span class="font green">+5,85</span></span></td></tr></table>
        """.trimIndent()

        assertEquals(
            WallstreetOnlineMover("Nemetschek", "/aktien/nemetschek-aktie", 61.5, 5.85),
            client.parseMovers(html).single()
        )
    }

    @Test
    fun `parses identified realtime quote`() {
        val date = LocalDate.of(2026, 8, 14)
        val now = date.atTime(10, 40).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        val html = """
            <h1 class="product-heading-heading ma-0 float-start">E.ON Aktie</h1>
            ISIN: <span class="cpyt isin value">DE000ENAG999</span>
            <div class="float-start quoteValue"><span data-push="3;ls;quotes;1@21@27;t">17,255</span></div>
            <div table="quotes" class="quote_currency">EUR</div>
            Letzter Kurs <span data-push=";ls;quotes;1@21@27;tt">10:36:33</span> Tradegate
        """.trimIndent()

        val quote = client.parseQuote(html, date, now)

        assertEquals("DE000ENAG999", quote.isin)
        assertEquals("E.ON", quote.name)
        assertEquals(17.255, quote.last)
        assertEquals("Tradegate", quote.venue)
        assertEquals(date.atTime(10, 36, 33).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(), quote.observedAtMillis)
    }
}
