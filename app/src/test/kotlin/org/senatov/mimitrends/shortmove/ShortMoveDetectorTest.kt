package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShortMoveDetectorTest {
    @Test
    fun `detects rapid crash at zero point five percent threshold`() {
        val now = 12_000L
        val bars = prices("THRESHOLD", now, 100.0, 100.15, 99.90, 99.50)
        val result = ShortMoveDetector.rank(mapOf("THRESHOLD" to bars), now).single()
        assertEquals(ShortMovePattern.RAPID_CRASH, result.pattern)
        assertEquals(-0.50, result.changePercent, 1e-9)
    }

    @Test
    fun `does not emit sub-threshold moves or rapid rises`() {
        val now = 13_000L
        val ranked = ShortMoveDetector.rank(
            mapOf(
                "SMALL_DROP" to prices("SMALL_DROP", now, 100.0, 99.90, 99.80, 99.501),
                "RISE" to prices("RISE", now, 100.0, 100.2, 100.7, 101.2)
            ), now
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `detects crash without requiring consecutive falling closes`() {
        val now = 13_500L
        val bars = listOf(bar("MIXED", now - 180, 100.0), bar("MIXED", now - 60, 100.3), bar("MIXED", now, 99.4))
        val result = ShortMoveDetector.rank(mapOf("MIXED" to bars), now).single()
        assertEquals(ShortMovePattern.RAPID_CRASH, result.pattern)
        assertEquals(-0.6, result.changePercent, 1e-9)
    }

    @Test
    fun `ignores stale and single-bar symbols`() {
        val now = 20_000L
        val ranked = ShortMoveDetector.rank(
            mapOf(
                "STALE" to prices("STALE", now - 11 * 60, 100.0, 99.0),
                "ONE" to listOf(bar("ONE", now, 99.0))
            ), now
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `retains a corridor briefly and drops it after a boundary break`() {
        val retainer = ShortMoveEventRetainer()
        val corridor = corridor("IFX.DE", 1_000L, 55.50, 55.93, 72)
        retainer.merge(listOf(corridor), 1_000L)
        assertTrue(retainer.merge(emptyList(), 1_900L).single().isRetained)
        val crash = ShortMove(
            "IFX.DE", -0.6, 55.50, 55.30, 1_000L, 1_060L, 4,
            ShortMovePattern.RAPID_CRASH, 1_060L
        )
        assertEquals(listOf(ShortMovePattern.RAPID_CRASH), retainer.merge(listOf(crash), 1_060L).map { it.pattern })
    }

    @Test
    fun `keeps only the strongest share class for one company`() {
        val moves = listOf(crash("GOOG", -3.13), crash("GOOGL", -2.89), crash("PLTR", -2.74))
        val distinct = ShortMoveCompanyRanking.distinct(moves, 10) { symbol ->
            if (symbol.startsWith("GOOG")) "Alphabet Inc." else "Palantir Technologies Inc."
        }
        assertEquals(listOf("GOOG", "PLTR"), distinct.map(ShortMove::symbol))
    }

    private fun prices(symbol: String, end: Long, vararg closes: Double) = closes.mapIndexed { index, close ->
        bar(symbol, end - (closes.lastIndex - index) * 60L, close)
    }

    private fun bar(symbol: String, time: Long, close: Double) =
        MinuteBar(symbol, time, close, close, close, close, 100.0)

    private fun crash(symbol: String, change: Double) = ShortMove(
        symbol, change, 100.0, 100.0 + change, 0L, 60L, 2, ShortMovePattern.RAPID_CRASH
    )

    private fun corridor(symbol: String, event: Long, lower: Double, upper: Double, score: Int) = ShortMove(
        symbol, (upper / lower - 1.0) * 100.0, lower, upper, event - 60L, event, 45,
        ShortMovePattern.TRADABLE_CORRIDOR, event, opportunityScore = score,
        corridorLower = lower, corridorUpper = upper
    )
}
