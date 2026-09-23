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

}
