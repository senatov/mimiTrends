package org.senatov.mimitrends.ws

import kotlin.test.Test
import kotlin.test.assertEquals

class FinnhubWebSocketClientTest {
    @Test
    fun `parses realtime trade messages`() {
        val client = FinnhubWebSocketClient("test", {}, {})
        val trades = client.parseTrades(
            """{"data":[{"p":189.5,"s":"AAPL","t":1710000000000,"v":4}],"type":"trade"}"""
        )
        assertEquals(1, trades.size)
        assertEquals("AAPL", trades.single().symbol)
        assertEquals(189.5, trades.single().price)
    }
}
