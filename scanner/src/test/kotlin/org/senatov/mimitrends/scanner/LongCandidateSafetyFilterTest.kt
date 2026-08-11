package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LongCandidateSafetyFilterTest {
    private val filter = LongCandidateSafetyFilter(ZoneOffset.UTC)

    @Test fun `allows an orderly rising session`() {
        val bars = (0 until 60).map { index ->
            val price = 100.0 + index * 0.02
            bar(index, price, price + 0.02, 100.0)
        }

        assertEquals("Impulse ↑", filter.classify(bars, result("Impulse ↑", 0.8, 5.0))?.signalSource)
    }

    @Test fun `rejects repeated heavy distribution during the session`() {
        var price = 100.0
        val bars = (0 until 60).map { index ->
            val open = price
            price = if (index == 35 || index == 48) price * 0.992 else price * 1.0001
            bar(index, open, price, if (index == 35 || index == 48) 300.0 else 100.0)
        }

        assertNull(filter.classify(bars, result("Impulse ↑", 0.8, 5.0)))
    }

    @Test fun `rejects a rise whose recent slope has broken`() {
        var price = 100.0
        val bars = (0 until 60).map { index ->
            val open = price
            price += if (index < 54) 0.03 else -0.05
            bar(index, open, price, 100.0)
        }

        assertNull(filter.classify(bars, result("Steady rise ↑", 1.2, 5.0)))
    }

    @Test fun `rejects a stalled tail after a strong recent climb`() {
        var price = 100.0
        val bars = (0 until 60).map { index ->
            val open = price
            price += when {
                index < 50 -> 0.01
                index < 55 -> 0.06
                else -> 0.0
            }
            bar(index, open, price, 100.0)
        }

        assertNull(filter.classify(bars, result("Steady rise ↑", 1.0, 5.0)))
    }

    @Test fun `keeps a truly sharp downside anomaly as a secondary watch`() {
        assertTrue(assertNotNull(filter.classify(emptyList(), result("Impulse ↓", -1.1, 5.2)))
            .signalSource.endsWith("· downside watch"))
        assertNull(filter.classify(emptyList(), result("Impulse ↓", -0.5, 5.2)))
    }

    private fun bar(index: Int, open: Double, close: Double, volume: Double) = MinuteBar(
        "TEST", 1_800_000_000L + index * 60L, open, maxOf(open, close), minOf(open, close), close, volume
    )

    private fun result(source: String, move: Double, z: Double) = ScanResult(
        symbol = "TEST", price = 100.0, anomalyScore = 6.0, priceAnomaly = z,
        volumeAnomaly = 3.0, rangeAnomaly = z, relativeVolume = 2.5,
        candleBodyRatio = 0.8, windowChangePercent = move, windowVolume = 1_000.0,
        sessionVolume = 10_000.0, sessionTurnover = 1_000_000.0, signalAgeMinutes = 0,
        signalSource = source, updatedAtMillis = 1_800_000_000_000L
    )
}
