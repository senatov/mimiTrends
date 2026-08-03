package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.VolumeStatus
import java.time.ZoneId

class VReversalStructureTest {
    @Test fun `rejects a small bullish V inside lower highs and lower lows`() {
        val bars = historicalBars().toMutableList()
        var minute = 4 * 1_440
        var previous = 102.0
        repeat(25) {
            val close = previous - 0.035
            bars += candle(minute++, previous, close)
            previous = close
        }
        repeat(3) {
            val close = previous - 0.11
            bars += candle(minute++, previous, close)
            previous = close
        }
        repeat(3) {
            val close = previous + 0.08
            bars += candle(minute++, previous, close)
            previous = close
        }

        assertNull(VReversalDetector(ZoneId.of("UTC")).detect(bars, criteria()))
    }

    private fun historicalBars() = (0 until 4).flatMap { day ->
        (0 until 30).map { minute -> candle(day * 1_440 + minute, 100.0, 100.01) }
    }

    private fun candle(minute: Int, open: Double, close: Double) = MinuteBar(
        "TEST", minute * 60L, open, maxOf(open, close) + 0.02,
        minOf(open, close) - 0.02, close, 500.0, VolumeStatus.REPORTED
    )

    private fun criteria() = ScannerCriteria(
        minPrice = 0.0,
        minJumpZ = 3.0,
        minAbsoluteMovePercent = 0.20
    )
}
