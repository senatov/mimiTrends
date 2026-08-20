package org.senatov.mimitrends.marketdata

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.LocalDate
import java.time.ZoneId

class WallstreetOnlineMarketDataClientTest {
    @Test fun `parses movers in document order before discovery ranks by performance`() {
        val html = """
            <table>
              <tr><td><a href='/aktien/slow-aktie'>Slow AG</a></td><td data-push='x;t'>10,00</td><td class='drel right'><span class='font green'>+1,00</span></td></tr>
              <tr><td><a href='/aktien/fast-aktie'>Fast AG</a></td><td data-push='x;t'>20,00</td><td class='drel right'><span class='font green'>+5,00</span></td></tr>
            </table>
        """.trimIndent()

        val movers = client.parseMovers(html).sortedByDescending(WallstreetOnlineMover::changePercent)

        assertEquals(listOf("Fast AG", "Slow AG"), movers.map(WallstreetOnlineMover::name))
    }

    private val client = WallstreetOnlineMarketDataClient()

    @Test fun `accepts only a resolved wallstreet online stock page`() {
        assertEquals("https://www.wallstreet-online.de/aktien/northern-data-aktie",
            client.validatedStockUrl(java.net.URI("https://www.wallstreet-online.de/suche/?q=x"),
                java.net.URI("https://www.wallstreet-online.de/aktien/northern-data-aktie?q=x")))
        kotlin.test.assertFailsWith<ProviderDataUnavailableException> {
            client.validatedStockUrl(java.net.URI("https://www.wallstreet-online.de/suche/?q=x"),
                java.net.URI("https://www.wallstreet-online.de/rohstoffe/goldpreis"))
        }
    }

    @Test fun `inserts a stock query while retaining search page parameters`() {
        val uri = client.searchUri("https://www.wallstreet-online.de/suche/?suche=&q=&sa=Suche", "Northern Data")
        assertEquals("https://www.wallstreet-online.de/suche/?suche=&sa=Suche&q=Northern+Data", uri.toString())
    }

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
