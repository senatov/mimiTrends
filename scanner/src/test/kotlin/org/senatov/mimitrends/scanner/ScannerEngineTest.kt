package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerEngineTest {
    @Test fun `detects a fresh upward impulse`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 103.0, 2_000.0)
        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))
        assertEquals("Impulse ↑", result.signalSource)
        assertEquals(0, result.signalAgeMinutes)
        assertTrue(result.priceAnomaly >= 3.0)
        assertTrue(result.candleBodyRatio >= 0.55)
    }

    @Test fun `detects a fresh downward impulse`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 96.0, 2_000.0)
        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))
        assertEquals("Impulse ↓", result.signalSource)
        assertTrue(result.windowChangePercent < 0.0)
    }

    @Test fun `does not rank volume without an exceptional price candle`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 100.01, 50_000.0)
        assertNull(engine().evaluate("TEST", bars, criteria()))
    }

    @Test fun `drops an impulse older than configured freshness horizon`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 104.0, 5_000.0)
        bars += candle(minute++, 104.0, 104.01, 100.0)
        bars += candle(minute++, 104.01, 104.02, 100.0)
        bars += candle(minute, 104.02, 104.03, 100.0)
        assertNull(engine().evaluate("TEST", bars, criteria(maxSignalAgeMinutes = 2)))
    }

    private fun normalBars() = (0 until 3).flatMap { day ->
        (0 until 30).map { minute -> candle(day * 1_440 + minute, 100.0, 100.01, 100.0) }
    }

    private fun nextMinute(bars: List<MinuteBar>) = (bars.last().minuteEpochSeconds / 60L + 1L).toInt()

    private fun candle(minute: Int, open: Double, close: Double, volume: Double): MinuteBar {
        val padding = if (kotlin.math.abs(close - open) > 1.0) 0.05 else 0.02
        return MinuteBar("TEST", minute * 60L, open, maxOf(open, close) + padding,
            minOf(open, close) - padding, close, volume)
    }

    private fun engine() = ScannerEngine(java.time.ZoneId.of("UTC"))
    private fun criteria(maxSignalAgeMinutes: Int = 2) = ScannerCriteria(
        minPrice = 0.0,
        maxSignalAgeMinutes = maxSignalAgeMinutes,
        minJumpZ = 3.0,
        minRangeZ = 3.5,
        minVolumeZ = 2.0,
        minRelativeVolume = 1.8,
        minBodyRatio = 0.55,
        minAbsoluteMovePercent = 0.10
    )
}
