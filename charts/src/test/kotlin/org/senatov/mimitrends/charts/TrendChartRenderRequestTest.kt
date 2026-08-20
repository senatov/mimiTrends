package org.senatov.mimitrends.charts

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.BrokerTrade
import kotlin.test.assertEquals

class TrendChartRenderRequestTest {
    @Test fun `keeps only trades belonging to the rendered instrument`() {
        val snow = trade("SNOW", 100L)
        val intel = trade("INTC", 200L)
        val request = TrendChartRenderRequest(
            "SNOW", "Snowflake", emptyList(), "1D", 1.0, "€", null, listOf(intel, snow)
        )

        assertEquals(listOf(snow), request.matchingTrades)
        assertEquals(listOf(100L, 160L), request.tradeEpochSeconds)
    }

    private fun trade(symbol: String, entry: Long) = BrokerTrade(
        symbol, null, 1.0, entry, 10.0, entry + 60L, 11.0, 1.0, 10.0, 0.0, "EUR"
    )
}
