package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.VolumeStatus
import java.time.ZoneId

class SteadyRiseActivityTest {
    @Test fun `stops calling a recovery active when its tail turns flat`() {
        val bars = historicalBars().toMutableList()
        var minute = 3 * 1_440
        var previous = 100.0
        repeat(12) {
            val close = previous + 0.06
            bars += candle(minute++, previous, close)
            previous = close
        }
        repeat(4) { bars += candle(minute++, previous, previous) }

        assertNull(SteadyRiseDetector(ZoneId.of("UTC")).detect("TEST", bars, ScannerCriteria(minPrice = 0.0)))
    }

    private fun historicalBars() = (0 until 3).flatMap { day ->
        (0 until 30).map { minute -> candle(day * 1_440 + minute, 100.0, 100.01) }
    }

    private fun candle(minute: Int, open: Double, close: Double) = MinuteBar(
        symbol = "TEST",
        minuteEpochSeconds = minute * 60L,
        open = open,
        high = maxOf(open, close) + 0.02,
        low = minOf(open, close) - 0.02,
        close = close,
        volume = 300.0,
        volumeStatus = VolumeStatus.REPORTED
    )
}
