package org.senatov.mimitrends.charts

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalChartPresentationTest {
    private val bars = listOf(
        bar(60L),
        bar(120L),
        bar(180L)
    )

    @Test
    fun `matches a signal to a nearby minute candle`() {
        assertEquals(bars[1], SignalChartPresentation.nearestBar(bars, 150L))
    }

    @Test
    fun `does not relocate a signal far outside loaded candles`() {
        assertNull(SignalChartPresentation.nearestBar(bars, 3_600L))
    }

    private fun bar(epoch: Long) = MinuteBar(
        "TEST", epoch, 100.0, 101.0, 99.0, 100.0, 1_000.0
    )
}