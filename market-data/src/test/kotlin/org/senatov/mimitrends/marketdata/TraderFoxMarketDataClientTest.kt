package org.senatov.mimitrends.marketdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TraderFoxMarketDataClientTest {
    private val client = TraderFoxMarketDataClient()

    @Test
    fun `parses quote and provider timestamp from stock payload`() {
        val html = """
            <script>var stock = { 'id': '' };</script>
            <script>var stock = {"id":"14854987","isin":"DE000ENER6Y0","name":"Siemens Energy AG",
                "currency":"EUR","v":152.39,"p":0.79,"t":1786015918};</script>
        """.trimIndent()

        val quote = client.parseQuote(html, "DE000ENER6Y0")!!

        assertEquals("DE000ENER6Y0", quote.isin)
        assertEquals("Siemens Energy AG", quote.name)
        assertEquals(152.39, quote.last)
        assertEquals("EUR", quote.currency)
        assertEquals(1_786_015_918_000L, quote.observedAtMillis)
    }

    @Test
    fun `rejects payload without provider timestamp`() {
        val html = """var stock = {"isin":"DE000ENER6Y0","name":"Siemens Energy AG","currency":"EUR","v":152.39};"""

        assertNull(client.parseQuote(html, "DE000ENER6Y0"))
    }
}
