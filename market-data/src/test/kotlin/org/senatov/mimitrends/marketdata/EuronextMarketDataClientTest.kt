package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EuronextMarketDataClientTest {
    private val client = EuronextMarketDataClient()

    @Test
    fun `selects an equity instead of a similarly named index`() {
        val selected = client.selectInstrument("""
            [
              {"isin":"FRIX00006976","mic":"XPAR","name":"EN G IN190525 D034",
               "link":"/en/product/indices/FRIX00006976-XPAR"},
              {"isin":"IT0000072618","mic":"MTAH","name":"INTESA SANPAOLO",
               "link":"/en/product/equities/IT0000072618-MTAH"}
            ]
        """.trimIndent())

        assertEquals(EuronextInstrument("IT0000072618", "MTAH", "INTESA SANPAOLO"), selected)
    }

    @Test
    fun `selects only the requested ISIN from ambiguous search results`() {
        val selected = client.selectInstrument("""
            [
              {"isin":"NO0013340802","mic":"XOSL","name":"ATLANTIC SAPPHI TR",
               "link":"/en/product/equities/NO0013340802-XOSL"},
              {"isin":"DE0007164600","mic":"ETLX","name":"SAP SE",
               "link":"/en/product/equities/DE0007164600-ETLX"}
            ]
        """.trimIndent(), "DE0007164600")

        assertEquals(EuronextInstrument("DE0007164600", "ETLX", "SAP SE"), selected)
    }

    @Test
    fun `reports text response instead of exposing Jackson parser error`() {
        assertFailsWith<ProviderResponseException> { client.selectInstrument("Cannot process request") }
        assertFailsWith<ProviderResponseException> { client.decodeQuoteBody("Cannot process request") }
        assertFailsWith<ProviderResponseException> { client.decodeQuoteBody("{}") }
    }

    @Test
    fun `classifies text response without including its body`() {
        val error = assertFailsWith<ProviderResponseException> {
            client.validateQuoteResponse("Cannot process request", "text/plain")
        }
        assertEquals("Euronext quote non-JSON response type=text/plain length=22 returned an invalid response", error.message)
    }

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

    @Test
    fun `parses English thousands separators in high prices`() {
        val quote = client.parseQuote("""
            <span id="header-instrument-currency">€</span>
            <span id="header-instrument-price">1,049.00</span>
            <div class="last-price-date-time">04/08/2026 - 14:43 &nbsp;CET</div>
            <span>Best Bid</span><span>1,048.20</span>
            <span>Best Ask</span><span>1,050.10</span>
        """.trimIndent())

        assertEquals(1_049.0, quote.last)
        assertEquals(1_048.2, quote.bid)
        assertEquals(1_050.1, quote.ask)
    }

    @Test
    fun `reports genuinely absent last trade as unavailable data`() {
        assertFailsWith<ProviderDataUnavailableException> {
            client.parseQuote("<span id=\"header-instrument-currency\">€</span>")
        }
    }
}
