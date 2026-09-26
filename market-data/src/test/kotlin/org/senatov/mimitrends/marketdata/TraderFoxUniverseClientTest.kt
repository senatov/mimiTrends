package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TraderFoxUniverseClientTest {
    @Test
    fun `reads listed active equities and normalizes US class shares`() {
        val html = """
            <script>var equities = {
              "1":{"symbol":"BRK.B","currency":"USD","v":498.5,"p":1.2,"t":1790366388,"active":"Y"},
              "2":{"symbol":"OLD","currency":"USD","v":0,"p":0,"t":1790366388,"active":"N"},
              "3":{"symbol":"MMM","currency":"USD","v":169.4,"p":-0.5,"t":1790366388,"active":"Y"}
            };</script>
        """.trimIndent()

        assertEquals(listOf("BRK-B", "MMM"), TraderFoxUniverseClient().parse(html).map { it.symbol })
    }
}
