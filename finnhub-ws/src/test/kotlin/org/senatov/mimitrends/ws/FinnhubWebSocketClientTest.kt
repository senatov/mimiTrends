package org.senatov.mimitrends.ws

import java.lang.reflect.Proxy
import java.net.http.WebSocket
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun `ignores late websocket callbacks after close`() {
        val trades = AtomicInteger()
        val errors = AtomicInteger()
        val client = FinnhubWebSocketClient("test", { trades.incrementAndGet() }, { errors.incrementAndGet() })
        val socket = Proxy.newProxyInstance(
            WebSocket::class.java.classLoader,
            arrayOf(WebSocket::class.java)
        ) { _, method, _ -> error("closed client must not call ${method.name}") } as WebSocket

        client.close()
        client.onText(socket, """{"data":[{"p":189.5,"s":"AAPL","t":1710000000000,"v":4}],"type":"trade"}""", true)
        client.onError(socket, IllegalStateException("late callback"))

        assertEquals(0, trades.get())
        assertEquals(0, errors.get())
    }
}
