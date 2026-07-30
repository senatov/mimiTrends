package org.senatov.mimitrends.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FinnhubClientTest {
    private val client = FinnhubClient("test-key")

    @Test
    fun `parses quote response`() {
        val quote = client.parseQuote(
            """{"c":211.18,"d":2.1,"dp":1.004,"h":212.3,"l":207.7,"o":208.4,"pc":209.08}"""
        )
        assertEquals(211.18, quote.current)
        assertEquals(1.004, quote.percentChange)
    }

    @Test
    fun `parses aligned candle arrays`() {
        val candles = client.parseCandles(
            """{"c":[100.0,102.5],"s":"ok","t":[1700000000,1700086400]}"""
        )
        assertEquals(2, candles.size)
        assertEquals(102.5, candles.last().close)
    }

    @Test
    fun `returns empty candles for no data`() {
        assertTrue(client.parseCandles("""{"s":"no_data"}""").isEmpty())
    }
}
