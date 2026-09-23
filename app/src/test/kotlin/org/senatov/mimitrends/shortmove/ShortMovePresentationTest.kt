package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShortMovePresentationTest {
    @Test
    fun `shows explicit rapid crash window`() {
        val move = move(ShortMovePattern.RAPID_CRASH, change = -0.67, open = 100.0, close = 99.33)

        assertEquals("-0.67% / 4m", ShortMovePresentation.movement(move))
        assertEquals(99.33, ShortMovePresentation.currentPrice(move), 0.0001)
    }

    @Test
    fun `shows corridor width and remaining room without opportunity probability`() {
        val move = move(
            ShortMovePattern.TRADABLE_CORRIDOR,
            change = 1.80,
            open = 102.0,
            close = 104.0,
            lower = 100.0,
            upper = 104.0
        )

        assertEquals("4.00% wide · +1.80% room", ShortMovePresentation.movement(move))
        assertEquals(102.0, ShortMovePresentation.currentPrice(move), 0.0001)
    }

    @Test
    fun `uses compact seconds minutes and hours for age`() {
        val move = move(ShortMovePattern.RAPID_CRASH, event = 1_000L)

        assertEquals("42s", ShortMovePresentation.age(move, 1_042L))
        assertEquals("2m", ShortMovePresentation.age(move, 1_120L))
        assertEquals("2h", ShortMovePresentation.age(move, 8_200L))
    }

    private fun move(
        pattern: ShortMovePattern,
        change: Double = -0.50,
        open: Double = 100.0,
        close: Double = 99.5,
        lower: Double? = null,
        upper: Double? = null,
        event: Long = 1_000L
    ) = ShortMove(
        symbol = "TEST",
        changePercent = change,
        open = open,
        close = close,
        startedAtEpochSeconds = event - 240L,
        endedAtEpochSeconds = event,
        barCount = 5,
        pattern = pattern,
        eventEpochSeconds = event,
        corridorLower = lower,
        corridorUpper = upper
    )
}
