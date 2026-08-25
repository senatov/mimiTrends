package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScalableCliClientTest {
    @Test
    fun `parses broker quote and derives previous close`() {
        val client = ScalableCliClient(commandRunner = {
            """{"ok":true,"data":{"result":{"isin":"DE000ENER6Y0","name":"Siemens Energy",
                "quote_currency":"EUR","quote_mid_price":151.23,"quote_bid_price":151.20,
                "quote_ask_price":151.26,"quote_timestamp_utc":"2026-08-25T08:02:34.314Z",
                "quote_performances":[{"timeframe":"INTRADAY","simple_absolute_return":2.33}]}}}"""
        })

        val quote = client.loadQuote("DE000ENER6Y0")

        assertEquals("Siemens Energy", quote.name)
        assertEquals(151.23, quote.midpoint)
        assertEquals(151.20, quote.bid)
        assertEquals(148.90, quote.previousClose!!, 0.0001)
        assertEquals(1_787_644_954_314L, quote.observedAtMillis)
    }

    @Test
    fun `rejects failed or incomplete responses`() {
        assertFailsWith<ScalableCliUnavailableException> {
            ScalableCliClient(commandRunner = { "{\"ok\":false}" }).loadQuote("DE000ENER6Y0")
        }
        assertFailsWith<ScalableCliUnavailableException> {
            ScalableCliClient(commandRunner = { "{\"ok\":true,\"data\":{\"result\":{}}}" })
                .loadQuote("DE000ENER6Y0")
        }
    }
}