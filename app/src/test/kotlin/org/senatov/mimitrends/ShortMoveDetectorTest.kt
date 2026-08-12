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

    @Test
    fun `keeps the full retained drop after volatile trading near the low`() {
        val now = 30_000L
        val closes = listOf(470.0, 470.5, 459.0, 457.5, 461.0, 456.5, 459.8)
        val recent = closes.mapIndexed { index, close ->
            bar("LVMH", now - (closes.lastIndex - index) * 60,
                if (index == 0) 470.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("LVMH" to recent), now).single()

        assertEquals(ShortMovePattern.POST_DROP_STRUGGLE, result.pattern)
        assertTrue(result.changePercent < -2.0)
        assertEquals(470.5, result.open)
        assertEquals(459.8, result.close)
        assertEquals((459.8 / 470.5 - 1.0) * 100.0, result.changePercent, 1e-9)
    }

    @Test
    fun `keeps an opening collapse visible later in the same session`() {
        val now = 50_000L
        val opening = listOf(470.0, 458.0, 456.0, 459.0)
        val later = (1..20).map { index -> 458.0 + (index % 3) * 0.2 }
        val closes = opening + later
        val session = closes.mapIndexed { index, close ->
            bar("LVMH", now - (closes.lastIndex - index) * 60,
                if (index == 0) 470.0 else closes[index - 1], close)
        }

        val result = ShortMoveDetector.rank(mapOf("LVMH" to session), now).single()

        assertEquals(ShortMovePattern.POST_DROP_STRUGGLE, result.pattern)
        assertTrue(result.changePercent < -2.0)
        assertEquals(470.0, result.open)
    }

    @Test
    fun `does not turn a gradual session drift into a sudden post drop`() {
        val now = 60_000L
        val session = (0 until 30).map { index ->
            val open = 100.0 - index * 0.1
            bar("DRIFT", now - (29 - index) * 60, open, open - 0.1)
        }

        val result = ShortMoveDetector.rank(mapOf("DRIFT" to session), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
        assertTrue(result.changePercent > -1.0)
    }

    @Test
    fun `does not treat distant sparse bars as a five minute collapse`() {
        val now = 70_000L
        val session = listOf(
            bar("SPARSE", now - 30 * 60, 100.0, 100.0),
            bar("SPARSE", now - 4 * 60, 90.0, 90.0),
            bar("SPARSE", now - 2 * 60, 90.0, 90.0),
            bar("SPARSE", now, 90.0, 90.0)
        )

        val result = ShortMoveDetector.rank(mapOf("SPARSE" to session), now).single()

        assertEquals(ShortMovePattern.DIRECTIONAL, result.pattern)
        assertEquals(0.0, result.changePercent, 1e-9)
    }

    @Test
    fun `keeps only the strongest share class for one company`() {
        val moves = listOf(
            move("GOOG", -3.13),
            move("GOOGL", -2.89),
            move("PLTR", -2.74)
        )

        val distinct = ShortMoveCompanyRanking.distinct(moves, 10) { symbol ->
            if (symbol.startsWith("GOOG")) "Alphabet Inc." else "Palantir Technologies Inc."
        }

        assertEquals(listOf("GOOG", "PLTR"), distinct.map(ShortMove::symbol))
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

    private fun move(symbol: String, change: Double) =
        ShortMove(symbol, change, 100.0, 100.0 + change, 0L, 60L, 2)
}
