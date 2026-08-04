package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TradegateMarketDataClientTest {
    @Test
    fun `parses localized quote and server observation time`() {
        val quote = TradegateMarketDataClient().parseQuote(
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
}
