package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RangeRegimeFilterTest {
    @Test
    fun blocksUpwardCandleInsideOscillatingRange() {
        val context = oscillatingBars()
        val candidate = bar(12, 99.8, 100.2)

        assertTrue(RangeRegimeFilter.blocks(context + candidate, candidate, 1))
    }

    @Test
    fun allowsConfirmedBreakAboveOscillatingRange() {
        val context = oscillatingBars()
        val candidate = bar(12, 99.8, 100.8)

        assertFalse(RangeRegimeFilter.blocks(context + candidate, candidate, 1))
    }

    @Test
    fun allowsOrdinaryDirectionalTrend() {
        val context: List<MinuteBar> = (0 until 12).map { minute ->
            bar(minute, 100.0 + minute * 0.05, 100.05 + minute * 0.05)
        }
        val candidate = bar(12, 100.60, 100.80)

        assertFalse(RangeRegimeFilter.blocks(context + candidate, candidate, 1))
    }

    private fun oscillatingBars(): List<MinuteBar> = (0 until 12).map { minute ->
        if (minute % 2 == 0) bar(minute, 100.2, 99.8) else bar(minute, 99.8, 100.2)
    }

    private fun bar(minute: Int, open: Double, close: Double): MinuteBar = MinuteBar(
        "TEST", minute * 60L, open, maxOf(open, close) + 0.02,
        minOf(open, close) - 0.02, close, 1_000.0
    )
}
