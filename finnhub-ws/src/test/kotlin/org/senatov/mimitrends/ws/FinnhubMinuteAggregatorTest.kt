package org.senatov.mimitrends.ws

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.TradeTick
import kotlin.test.assertEquals

class FinnhubMinuteAggregatorTest {
    @Test fun `aggregates trades into an updatable minute bar`() {
        var bar: MinuteBar? = null
        val aggregator = FinnhubMinuteAggregator { bar = it }
        aggregator.accept(TradeTick("AAPL", 100.0, 60_001, 5.0))
        aggregator.accept(TradeTick("AAPL", 102.0, 75_000, 7.0))
        aggregator.accept(TradeTick("AAPL", 99.0, 80_000, 3.0))
        assertEquals(null, bar)
        aggregator.accept(TradeTick("AAPL", 101.0, 120_001, 2.0))
        assertEquals(100.0, bar?.open)
        assertEquals(102.0, bar?.high)
        assertEquals(99.0, bar?.low)
        assertEquals(99.0, bar?.close)
        assertEquals(15.0, bar?.volume)
    }
}
