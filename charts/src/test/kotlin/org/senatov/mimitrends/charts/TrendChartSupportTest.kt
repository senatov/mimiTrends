package org.senatov.mimitrends.charts

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrendChartSupportTest {
    @Test
    fun `aggregation never combines candles from separate market sessions`() {
        val bars = listOf(
            bar(0L, 100.0),
            bar(60L, 101.0),
            bar(63_120L, 200.0),
            bar(63_180L, 201.0)
        )

        val aggregated = TrendChartSupport.aggregate(bars, 2)

        assertEquals(2, aggregated.size)
        assertEquals(listOf(100.0, 200.0), aggregated.map(MinuteBar::open))
        assertEquals(listOf(101.0, 201.0), aggregated.map(MinuteBar::close))
        assertTrue(aggregated.none { it.low < 150.0 && it.high > 150.0 })
    }

    @Test
    fun `session-aware aggregation remains bounded`() {
        val bars = (0 until 20).flatMap { session ->
            (0 until 10).map { minute -> bar(session * 86_400L + minute * 60L, session * 10.0 + minute) }
        }

        val aggregated = TrendChartSupport.aggregate(bars, 30)

        assertTrue(aggregated.size <= 30)
        assertEquals(20, aggregated.map { it.minuteEpochSeconds / 86_400L }.distinct().size)
    }

    private fun bar(epoch: Long, price: Double) = MinuteBar(
        "TEST", epoch, price, price + 1.0, price - 1.0, price, 1_000.0
    )
}