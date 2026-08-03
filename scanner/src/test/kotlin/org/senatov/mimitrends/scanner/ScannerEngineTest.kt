package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerEngineTest {
    @Test fun `uses the configured freshness duration as a true half life`() {
        assertEquals(1.0, engine().freshnessWeight(0.0), 1e-12)
        assertEquals(0.5, engine().freshnessWeight(1.8), 1e-12)
    }

    @Test fun `detects a fresh upward impulse`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 103.0, 2_000.0)
        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))
        assertEquals("Impulse ↑", result.signalSource)
        assertEquals(0, result.signalAgeMinutes)
        assertEquals(103.0, result.signalPrice)
        assertEquals(bars.last().minuteEpochSeconds * 1_000L, result.signalEpochMillis)
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

    @Test fun `detects an early three minute rise before any single candle qualifies`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 100.17, 900.0)
        bars += candle(minute++, 100.17, 100.34, 1_100.0)
        bars += candle(minute, 100.34, 100.51, 1_300.0)

        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))

        assertEquals("Momentum 3m ↑", result.signalSource)
        assertEquals("3m acceleration", result.signalWindowLabel)
        assertTrue(result.windowChangePercent >= 0.50)
        assertTrue(result.anomalyScore >= 4.0)
    }

    @Test fun `detects an early three minute fall symmetrically`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 99.83, 900.0)
        bars += candle(minute++, 99.83, 99.66, 1_100.0)
        bars += candle(minute, 99.66, 99.49, 1_300.0)

        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))

        assertEquals("Momentum 3m ↓", result.signalSource)
        assertTrue(result.windowChangePercent <= -0.50)
    }

    @Test fun `drops early momentum after ten flat minutes`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 100.17, 900.0)
        bars += candle(minute++, 100.17, 100.34, 1_100.0)
        bars += candle(minute++, 100.34, 100.51, 1_300.0)
        repeat(10) { bars += candle(minute++, 100.51, 100.51, 100.0) }

        assertNull(engine().evaluate("TEST", bars, criteria()))
    }

    @Test fun `rejects a noisy three minute path with weak directional efficiency`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 100.50, 900.0)
        bars += candle(minute++, 100.50, 99.90, 1_100.0)
        bars += candle(minute, 99.90, 100.40, 1_300.0)

        assertNull(EarlyMomentumDetector(java.time.ZoneId.of("UTC")).detect(bars, criteria()))
    }

    @Test fun `detects a Generali-like bullish V reversal after the rebound starts`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 99.86, 600.0)
        bars += candle(minute++, 99.86, 99.72, 800.0)
        bars += candle(minute++, 99.72, 99.80, 900.0)
        bars += candle(minute++, 99.80, 99.91, 1_000.0)
        bars += candle(minute, 99.91, 100.01, 1_100.0)

        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))

        assertEquals("V-Reversal ↑", result.signalSource)
        assertTrue(result.windowChangePercent >= 0.25)
        assertTrue(result.priceAnomaly >= 3.0)
    }

    @Test fun `detects a bearish V reversal symmetrically`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 100.14, 600.0)
        bars += candle(minute++, 100.14, 100.28, 800.0)
        bars += candle(minute++, 100.28, 100.20, 900.0)
        bars += candle(minute++, 100.20, 100.09, 1_000.0)
        bars += candle(minute, 100.09, 99.99, 1_100.0)

        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))

        assertEquals("V-Reversal ↓", result.signalSource)
        assertTrue(result.windowChangePercent <= -0.25)
    }

    @Test fun `does not call an unrecovered fall a V reversal`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 99.85, 600.0)
        bars += candle(minute++, 99.85, 99.70, 800.0)
        bars += candle(minute++, 99.70, 99.68, 900.0)
        bars += candle(minute, 99.68, 99.67, 1_000.0)

        assertNull(VReversalDetector(java.time.ZoneId.of("UTC")).detect(bars, criteria()))
    }

    @Test fun `expires a V reversal after ten inactive minutes`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 99.85, 600.0)
        bars += candle(minute++, 99.85, 99.70, 800.0)
        bars += candle(minute++, 99.70, 99.82, 900.0)
        bars += candle(minute++, 99.82, 99.94, 1_000.0)
        repeat(10) { bars += candle(minute++, 99.94, 99.94, 100.0) }

        assertNull(VReversalDetector(java.time.ZoneId.of("UTC")).detect(bars, criteria()))
    }

    @Test fun `detects a multi-scale reversal after a six minute selloff`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        var previous = 100.0
        repeat(6) {
            val close = previous - 0.07
            bars += candle(minute++, previous, close, 600.0)
            previous = close
        }
        repeat(3) {
            val close = previous + 0.11
            bars += candle(minute++, previous, close, 900.0)
            previous = close
        }

        val reversal = requireNotNull(VReversalDetector(java.time.ZoneId.of("UTC")).detect(bars, criteria()))

        assertEquals(1, reversal.direction)
        assertTrue(reversal.shockPercent >= 0.40)
        assertTrue(reversal.recoveryRatio >= 0.60)
    }

    @Test fun `rejects a slow weak bounce after a fast fall`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        bars += candle(minute++, 100.0, 99.84, 800.0)
        bars += candle(minute++, 99.84, 99.68, 900.0)
        var previous = 99.68
        repeat(8) {
            val close = previous + 0.027
            bars += candle(minute++, previous, close, 500.0)
            previous = close
        }

        assertNull(VReversalDetector(java.time.ZoneId.of("UTC")).detect(bars, criteria()))
    }

    @Test fun `keeps a price impulse but marks zero volume as unavailable`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 96.0, 0.0, VolumeStatus.MISSING)

        val result = requireNotNull(engine().evaluate("TEST", bars, criteria()))

        assertTrue(result.volumeAnomaly.isNaN())
        assertTrue(result.relativeVolume.isNaN())
        assertEquals(0.0, result.windowVolume)
    }

    @Test fun `requires three historical sessions for an impulse baseline`() {
        val bars = normalBars(days = 3).toMutableList()
        bars += candle(nextMinute(bars), 100.0, 104.0, 5_000.0)

        assertNull(engine().evaluate("TEST", bars, criteria()))
    }

    @Test fun `does not use an immaterial previous return as confirmation`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 100.001, 100.0)
        bars += candle(nextMinute(bars), 100.001, 100.30, 0.0, VolumeStatus.MISSING)
        val demandingBodyCriteria = criteria().copy(minBodyRatio = 0.99)

        assertNull(engine().evaluate("TEST", bars, demandingBodyCriteria))
    }

    @Test fun `does not rank volume without an exceptional price candle`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 100.01, 50_000.0)
        assertNull(engine().evaluate("TEST", bars, criteria()))
    }

    @Test fun `broad adaptive tier admits a near-threshold move rejected by the mild tier`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 100.19, 5_000.0)

        assertNull(engine().evaluateFallback("TEST", bars, criteria(), 0.85))
        val result = requireNotNull(engine().evaluateFallback("TEST", bars, criteria(), 0.55))

        assertTrue(result.signalSource.contains("relaxed"))
    }

    @Test fun `rejects a statistically unusual but economically tiny candle`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars), 100.0, 100.10, 10_000.0)
        assertNull(engine().evaluate("TEST", bars, criteria()))
    }

    @Test fun `does not treat a multi minute data gap as a one minute impulse`() {
        val bars = normalBars().toMutableList()
        bars += candle(nextMinute(bars) + 2, 104.0, 104.01, 5_000.0)

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

    @Test fun `uses a persistent half-session rise as fallback`() {
        val bars = mutableListOf<MinuteBar>()
        (0 until 2).forEach { day ->
            (0 until 180).forEach { minute -> bars += candle(day * 1_440 + minute, 100.0, 100.01, 100.0) }
        }
        var previous = 100.0
        (0 until 180).forEach { minute ->
            val close = 100.0 + minute * 0.08 + kotlin.math.sin(minute / 7.0) * 0.08
            bars += candle(2 * 1_440 + minute, previous, close, 150.0)
            previous = close
        }
        assertNull(engine().evaluate("TEST", bars, criteria()))
        val result = requireNotNull(engine().evaluateFallback("TEST", bars, criteria()))
        assertEquals("Steady rise ↑", result.signalSource)
        assertTrue(result.signalWindowLabel.endsWith("m steady"))
        assertTrue(result.windowChangePercent >= 0.30)
    }

    @Test fun `rejects an old trend that is flat now`() {
        val bars = mutableListOf<MinuteBar>()
        (0 until 2).forEach { day ->
            (0 until 180).forEach { minute -> bars += candle(day * 1_440 + minute, 100.0, 100.01, 100.0) }
        }
        var previous = 100.0
        (0 until 180).forEach { minute ->
            val close = if (minute < 140) 100.0 + minute * 0.04 else 105.6 + (minute % 2) * 0.01
            bars += candle(2 * 1_440 + minute, previous, close, 150.0)
            previous = close
        }
        assertNull(engine().evaluateFallback("TEST", bars, criteria()))
    }

    @Test fun `does not call a single jump followed by shallow drift a steady rise`() {
        val bars = normalBars(days = 3).toMutableList()
        var minute = 3 * 1_440
        var previous = 100.0
        repeat(12) {
            bars += candle(minute++, previous, previous + 0.01, 300.0)
            previous += 0.01
        }
        bars += candle(minute++, previous, 105.0, 5_000.0)
        previous = 105.0
        repeat(8) {
            bars += candle(minute++, previous, previous + 0.025, 300.0)
            previous += 0.025
        }

        assertNull(SteadyRiseDetector(java.time.ZoneId.of("UTC")).detect("TEST", bars, criteria()))
    }

    @Test fun `recommends a clean recent staircase rise`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        var previous = 100.0
        repeat(31) { index ->
            val close = previous + 0.035 + kotlin.math.sin(index.toDouble()) * 0.004
            bars += candle(minute++, previous, close, 300.0)
            previous = close
        }

        val result = requireNotNull(engine().evaluateFallback("TEST", bars, criteria()))

        assertEquals("Steady rise ↑", result.signalSource)
        assertTrue(result.candleBodyRatio >= 0.80)
        assertTrue(result.anomalyScore >= 3.0)
    }

    @Test fun `recognizes a strong SAP-like opening rise within eleven minutes`() {
        val bars = normalBars(days = 3).toMutableList()
        var minute = 3 * 1_440
        val prices = listOf(160.76, 159.74, 159.94, 160.10, 160.32, 160.44,
            160.56, 160.94, 161.58, 161.74, 161.94, 162.34)
        var previous = 160.0
        prices.forEach { close ->
            bars += candle(minute++, previous, close, 5_000.0)
            previous = close
        }

        val result = requireNotNull(SteadyRiseDetector(java.time.ZoneId.of("UTC"))
            .detect("TEST", bars, criteria()))

        assertEquals("Steady rise ↑", result.signalSource)
        assertEquals("10m steady", result.signalWindowLabel)
        assertTrue(result.windowChangePercent >= 1.50)
    }

    @Test fun `rejects a sideways path even when it is visually smooth`() {
        val bars = normalBars().toMutableList()
        var minute = nextMinute(bars)
        var previous = 100.0
        repeat(31) { index ->
            val close = 100.0 + kotlin.math.sin(index / 4.0) * 0.04
            bars += candle(minute++, previous, close, 300.0)
            previous = close
        }

        assertNull(SteadyRiseDetector(java.time.ZoneId.of("UTC")).detect("TEST", bars, criteria()))
    }

    @Test fun `labels a continuing bounce inside an established decline as recovery`() {
        val bars = normalBars(days = 3).toMutableList()
        var minute = 3 * 1_440
        var previous = 100.0
        repeat(51) {
            val close = previous - 0.02
            bars += candle(minute++, previous, close, 300.0)
            previous = close
        }
        repeat(10) {
            val close = previous + 0.05
            bars += candle(minute++, previous, close, 300.0)
            previous = close
        }

        val result = requireNotNull(SteadyRiseDetector(java.time.ZoneId.of("UTC")).detect("TEST", bars, criteria()))

        assertEquals("Recovery rise ↑", result.signalSource)
        assertTrue(result.anomalyScore > 0.0)
    }

    @Test fun `detects a rise when liquid quotes have short gaps`() {
        val bars = normalBars(days = 3).toMutableList()
        var minute = 3 * 1_440
        var previous = 100.0
        repeat(21) { index ->
            if (index !in setOf(4, 9, 15)) {
                val close = previous + 0.04
                bars += candle(minute, previous, close, 300.0)
                previous = close
            }
            minute++
        }

        val result = requireNotNull(SteadyRiseDetector(java.time.ZoneId.of("UTC"))
            .detect("TEST", bars, criteria()))

        assertEquals("Steady rise ↑", result.signalSource)
    }

    private fun normalBars(days: Int = 4) = (0 until days).flatMap { day ->
        (0 until 30).map { minute -> candle(day * 1_440 + minute, 100.0, 100.01, 100.0) }
    }

    private fun nextMinute(bars: List<MinuteBar>) = (bars.last().minuteEpochSeconds / 60L + 1L).toInt()

    private fun candle(
        minute: Int,
        open: Double,
        close: Double,
        volume: Double,
        volumeStatus: VolumeStatus = if (volume > 0.0) VolumeStatus.REPORTED else VolumeStatus.ZERO
    ): MinuteBar {
        val padding = if (kotlin.math.abs(close - open) > 1.0) 0.05 else 0.02
        return MinuteBar("TEST", minute * 60L, open, maxOf(open, close) + padding,
            minOf(open, close) - padding, close, volume, volumeStatus)
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
        minAbsoluteMovePercent = 0.20
    )
}
