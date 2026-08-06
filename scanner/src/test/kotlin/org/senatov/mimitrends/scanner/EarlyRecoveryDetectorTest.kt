package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EarlyRecoveryDetectorTest {
    private val detector = EarlyRecoveryDetector(ZoneOffset.UTC)

    @Test fun `detects a base with higher lows after an intraday decline`() {
        val bars = historicalBars() + recoverySession()

        val result = assertNotNull(detector.detect("TEST", bars, criteria()))

        assertEquals("Early recovery ↑ · watch", result.signalSource)
        assertTrue(result.windowChangePercent >= 0.35)
        assertTrue(result.signalWindowLabel.endsWith("m recovery"))
    }

    @Test fun `rejects a rebound that returns to the session low`() {
        val bars = (historicalBars() + recoverySession()).toMutableList()
        val last = bars.last()
        bars[bars.lastIndex] = bar(last.minuteEpochSeconds / 60L, last.open, 98.75)

        assertNull(detector.detect("TEST", bars, criteria()))
    }

    private fun historicalBars() = (0 until 3).flatMap { day ->
        (0 until 30).map { minute -> bar(day * 1_440L + minute, 100.0, 100.01) }
    }

    private fun recoverySession(): List<MinuteBar> {
        val bars = mutableListOf<MinuteBar>()
        var minute = 3 * 1_440L
        var price = 100.0
        repeat(25) {
            val next = price + 0.005
            bars += bar(minute++, price, next)
            price = next
        }
        repeat(8) {
            val next = price - 0.16
            bars += bar(minute++, price, next)
            price = next
        }
        repeat(16) {
            val next = price + 0.045
            bars += bar(minute++, price, next)
            price = next
        }
        return bars
    }

    private fun bar(minute: Long, open: Double, close: Double) = MinuteBar(
        symbol = "TEST",
        minuteEpochSeconds = minute * 60L,
        open = open,
        high = maxOf(open, close) + 0.01,
        low = minOf(open, close) - 0.01,
        close = close,
        volume = 1_000.0
    )

    private fun criteria() = ScannerCriteria(minPrice = 0.0, minSessionTurnover = 0.0)
}
