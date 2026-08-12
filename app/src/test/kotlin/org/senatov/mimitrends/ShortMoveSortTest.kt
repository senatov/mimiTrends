package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private fun shortMove(
    symbol: String,
    change: Double,
    pattern: ShortMovePattern = ShortMovePattern.DIRECTIONAL,
    start: Long = 0L,
    end: Long = 60L
) = ShortMove(symbol, change, 100.0, 100.0 * (1.0 + change / 100.0), start, end, 2, pattern)

class ShortMoveSortTest {
    @Test
    fun `move and price range use signed percentage`() {
        val moves: List<ShortMove> = listOf(
            shortMove("UP", 3.0),
            shortMove("DOWN", -4.0),
            shortMove("SMALL", -1.0)
        )

        assertEquals(listOf("DOWN", "SMALL", "UP"), moves.sortedWith(ShortMoveSort.priceChange).map(ShortMove::symbol))
    }

    @Test
    fun `direction follows the signed axis with post drop at the negative edge`() {
        val moves: List<ShortMove> = listOf(
            shortMove("UP", 2.0),
            shortMove("DOWN", -2.0),
            shortMove("POST", -3.0, ShortMovePattern.POST_DROP_STRUGGLE)
        )

        assertEquals(listOf("POST", "DOWN", "UP"), moves.sortedWith(ShortMoveSort.direction).map(ShortMove::symbol))
    }

    @Test
    fun `period sorts intervals by their position on the time axis`() {
        val late = shortMove("LATE", 1.0, start = 300L, end = 500L)
        val early = shortMove("EARLY", 1.0, start = 100L, end = 200L)
        val middle = shortMove("MIDDLE", 1.0, start = 180L, end = 300L)

        assertEquals(listOf("EARLY", "MIDDLE", "LATE"),
            listOf(late, early, middle).sortedWith(ShortMoveSort.period).map(ShortMove::symbol))
    }

    @Test
    fun `reapplies selected sorting after refreshed rows replace the list`() {
        val rows = mutableListOf(shortMove("OLD", 1.0))
        rows.clear()
        rows += listOf(shortMove("UP", 0.5), shortMove("DOWN", -0.5), shortMove("FLAT", 0.0))

        ShortMoveSort.apply(rows, ShortMoveSort.priceChange)

        assertEquals(listOf("DOWN", "FLAT", "UP"), rows.map(ShortMove::symbol))
    }

}
