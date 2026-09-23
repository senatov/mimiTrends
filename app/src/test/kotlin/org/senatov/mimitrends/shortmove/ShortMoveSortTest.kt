package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private fun shortMove(
    symbol: String,
    pattern: ShortMovePattern,
    start: Long = 0L,
    end: Long = 60L,
    open: Double = 100.0,
    change: Double = -0.6
) = ShortMove(symbol, change, open, open * (1.0 + change / 100.0), start, end, 2, pattern)

class ShortMoveSortTest {
    @Test
    fun `rapid crashes sort ahead of corridors`() {
        val moves = listOf(
            shortMove("CORRIDOR", ShortMovePattern.TRADABLE_CORRIDOR, change = 1.2),
            shortMove("CRASH", ShortMovePattern.RAPID_CRASH)
        )
        assertEquals(listOf("CRASH", "CORRIDOR"), moves.sortedWith(ShortMoveSort.direction).map(ShortMove::symbol))
    }

    @Test
    fun `price range sorts by displayed opening price`() {
        val moves = listOf(
            shortMove("CHEAP", ShortMovePattern.TRADABLE_CORRIDOR, open = 20.0),
            shortMove("EXPENSIVE", ShortMovePattern.RAPID_CRASH, open = 300.0),
            shortMove("MIDDLE", ShortMovePattern.RAPID_CRASH, open = 100.0)
        )
        assertEquals(
            listOf("CHEAP", "MIDDLE", "EXPENSIVE"),
            moves.sortedWith(ShortMoveSort.priceRange).map(ShortMove::symbol)
        )
    }

    @Test
    fun `period sorts intervals by their position on the time axis`() {
        val late = shortMove("LATE", ShortMovePattern.RAPID_CRASH, start = 300L, end = 500L)
        val early = shortMove("EARLY", ShortMovePattern.RAPID_CRASH, start = 100L, end = 200L)
        val middle = shortMove("MIDDLE", ShortMovePattern.RAPID_CRASH, start = 180L, end = 300L)
        assertEquals(
            listOf("EARLY", "MIDDLE", "LATE"),
            listOf(late, early, middle).sortedWith(ShortMoveSort.period).map(ShortMove::symbol)
        )
    }
}
