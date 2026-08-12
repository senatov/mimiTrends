package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortMoveDetectorTest {
    @Test
    fun `ranks upward and downward candles by absolute move`() {
        val now = 10_000L
        val ranked = ShortMoveDetector.rank(mapOf(
            "UP" to bars("UP", now, 103.0),
            "DOWN" to bars("DOWN", now, 94.0),
            "FLAT" to bars("FLAT", now, 100.5)
        ), now, limit = 2)

        assertEquals(listOf("DOWN", "UP"), ranked.map(ShortMove::symbol))
        assertTrue(ranked.first().changePercent < 0.0)
        assertEquals(5, ranked.first().barCount)
    }

    @Test
    fun `ignores stale and single-bar symbols`() {
        val now = 10_000L
        val ranked = ShortMoveDetector.rank(mapOf(
            "STALE" to bars("STALE", now - 11 * 60, 110.0),
            "ONE" to listOf(bar("ONE", now, 100.0, 101.0))
        ), now)

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `detects a completed drop followed by small mixed moves`() {
        val now = 20_000L
        val closes = listOf(100.0, 99.8, 96.9, 96.5, 96.8, 96.4, 96.7)
        val bars = closes.mapIndexed { index, close ->
            bar("BATTLE", now - (closes.lastIndex - index) * 60, if (index == 0) 100.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("BATTLE" to bars), now).single()

        assertEquals(ShortMovePattern.POST_DROP_STRUGGLE, result.pattern)
        assertTrue(result.changePercent < -3.0)
    }

    @Test
    fun `does not classify a strong v recovery as post drop struggle`() {
        val now = 20_000L
        val closes = listOf(100.0, 96.0, 96.3, 98.5, 99.5)
        val bars = closes.mapIndexed { index, close ->
            bar("RECOVERY", now - (closes.lastIndex - index) * 60, if (index == 0) 100.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("RECOVERY" to bars), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
    }

    private fun bars(symbol: String, end: Long, close: Double): List<MinuteBar> =
        (0 until 5).map { index ->
            val open = 100.0
            val stepOpen = open + (close - open) * index / 5.0
            val stepClose = open + (close - open) * (index + 1) / 5.0
            bar(symbol, end - (4 - index) * 60, stepOpen, stepClose)
        }

    private fun bar(symbol: String, time: Long, open: Double, close: Double) =
        MinuteBar(symbol, time, open, maxOf(open, close), minOf(open, close), close, 100.0)
}
