package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.senatov.mimitrends.model.MinuteBar

class TradableCorridorDetectorTest {
    @Test
    fun `detects a repeated stable corridor while price is near its lower edge`() {
        val now = 100_000L
        val pattern = listOf(99.0, 99.3, 99.8, 100.4, 100.9, 101.0, 100.7, 100.2, 99.7, 99.2, 99.0, 99.1)
        val bars = (0 until 72).map { index -> bar(now - (71 - index) * 60L, pattern[index % pattern.size]) }

        val result = TradableCorridorDetector.detect("NVDA", bars, now)

        requireNotNull(result)
        assertEquals(ShortMovePattern.TRADABLE_CORRIDOR, result.pattern)
        assertTrue(result.opportunityScore >= 60)
        assertTrue(result.changePercent > 1.0)
        assertTrue(result.opportunityDetails.contains("lower"))
    }

    @Test
    fun `does not mistake a one way trend for a corridor`() {
        val now = 200_000L
        val bars = (0 until 72).map { index -> bar(now - (71 - index) * 60L, 90.0 + index * 0.12) }

        assertNull(TradableCorridorDetector.detect("TREND", bars, now))
    }

    private fun bar(epoch: Long, close: Double) = MinuteBar(
        "TEST", epoch, close, close + 0.05, close - 0.05, close, 10_000.0
    )
}