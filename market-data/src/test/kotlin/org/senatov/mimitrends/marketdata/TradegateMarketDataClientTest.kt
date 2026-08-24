package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TradegateMarketDataClientTest {
    private val client = TradegateMarketDataClient()

    @Test
    fun `parses localized quote and server observation time`() {
        val quote = client.parseQuote(
            "US5128073062",
            """{"bid":263.95,"ask":265.65,"bidsize":60,"asksize":23,"stueck":2862,"umsatz":752505,
                "avg":262.9298,"executions":99,"last":"265,50","high":265.65,"low":"257,00","close":255.95}""",
            "Tue, 04 Aug 2026 10:35:49 GMT"
        )

        assertEquals(265.50, quote.last)
        assertEquals(263.95, quote.bid)
        assertEquals(257.0, quote.low)
        assertEquals(2_862.0, quote.sessionVolume)
        assertEquals(60.0, quote.bidSize)
        assertEquals(752_505.0, quote.sessionTurnover)
        assertEquals(99, quote.executions)
        assertEquals(1_785_839_749_000L, quote.observedAtMillis)
    }

    @Test
    fun `selects the underlying equity from search results`() {
        val html = """
            <tr class="kurssuche_ergebnis"><td><a href="orderbuch.php?lang=en&amp;isin=DE0006231004">
            <b>Infineon Technologies</b> AG <img src="images/basiswert.png" /></a></td></tr>
            <tr class="alt kurssuche_ergebnis"><td><a href="orderbuch.php?lang=en&amp;isin=XS2056730679">
            Infineon Technologies AG -FLR-Nts.v.19</a></td></tr>
        """.trimIndent()

        val instrument = client.parseInstrumentPage("Infineon", html)

        assertEquals("DE0006231004", instrument?.isin)
        assertEquals("Infineon Technologies AG", instrument?.name)
    }

    @Test
    fun `decodes German html entities in resolved instrument name`() {
        val html = """
            <tr class="kurssuche_ergebnis"><td><a href="orderbuch.php?lang=en&amp;isin=DE0008430026">
            <b>M&amp;uuml;nchener R&amp;uuml;ckvers.-Ges.</b> AG <img src="images/basiswert.png" /></a></td></tr>
        """.trimIndent().replace("&amp;uuml;", "&uuml;")

        val instrument = client.parseInstrumentPage("MUV2", html)

        assertEquals("Münchener Rückvers.-Ges. AG", instrument?.name)
    }

    @Test
    fun `rejects a directly opened debt instrument`() {
        val html = """
            <script>var isin = "US11135FCM14";</script>
            <script>var securityName = "Broadcom Inc. DL-Notes 2025(25/35)";</script>
        """.trimIndent()

        assertEquals(null, client.parseInstrumentPage("Broadcom Inc.", html))
    }

    @Test
    fun `reports missing last price as unavailable data`() {
        assertFailsWith<ProviderDataUnavailableException> {
            client.parseQuote("DE0006231004", "{\"bid\": 31.2}", null)
        }
    }
}
