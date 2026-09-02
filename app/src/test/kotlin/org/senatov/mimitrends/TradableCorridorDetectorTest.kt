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
        assertEquals((result.close / result.open - 1.0) * 100.0, result.changePercent, 1e-12)
        assertTrue(requireNotNull(result.corridorLower) < result.open)
        assertTrue(result.opportunityDetails.contains("lower"))
    }

    @Test
    fun `does not mistake a one way trend for a corridor`() {
        val now = 200_000L
        val bars = (0 until 72).map { index -> bar(now - (71 - index) * 60L, 90.0 + index * 0.12) }

        assertNull(TradableCorridorDetector.detect("TREND", bars, now))
    }

    @Test
    fun `does not count stale sparse bars as a two hour corridor`() {
        val now = 2_000_000L
        val pattern = listOf(99.0, 100.0, 101.0, 100.0)
        val bars = (0 until 72).map { index ->
            bar(now - (71 - index) * 10L * 60L, pattern[index % pattern.size])
        }

        assertNull(TradableCorridorDetector.detect("NVDA", bars, now))
    }

    @Test
    fun `rejects a corridor after the latest price breaks its lower edge`() {
        val now = 300_000L
        val pattern = listOf(99.0, 99.3, 99.8, 100.4, 100.9, 101.0, 100.7, 100.2, 99.7, 99.2, 99.0, 99.1)
        val bars = (0 until 71).map { index ->
            bar(now - (71 - index) * 60L, pattern[index % pattern.size])
        } + bar(now, 97.5)

        assertNull(TradableCorridorDetector.detect("NVDA", bars, now))
    }

    @Test
    fun `rejects a corridor with repeated recent lower edge breaks`() {
        val now = 400_000L
        val pattern = listOf(99.0, 99.3, 99.8, 100.4, 100.9, 101.0, 100.7, 100.2, 99.7, 99.2, 99.0, 99.1)
        val closes = (0 until 72).map { pattern[it % pattern.size] }.toMutableList().apply {
            this[lastIndex - 4] = 97.5
            this[lastIndex - 2] = 97.6
            this[lastIndex] = 99.1
        }
        val bars = closes.mapIndexed { index, close -> bar(now - (71 - index) * 60L, close) }

        assertNull(TradableCorridorDetector.detect("NVDA", bars, now))
    }

    private fun bar(epoch: Long, close: Double) = MinuteBar(
        "TEST", epoch, close, close + 0.05, close - 0.05, close, 10_000.0
    )
}
