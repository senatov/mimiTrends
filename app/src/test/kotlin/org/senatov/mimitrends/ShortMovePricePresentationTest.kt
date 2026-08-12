package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ShortMovePricePresentationTest {
    @Test
    fun `shows start and end prices on separate lines`() {
        val move = ShortMove("SAP.DE", -2.5, 123.45, 120.36, 1_000L, 1_300L, 5)

        assertEquals("123.45 →\n120.36", ShortMovePricePresentation.text(move))
    }
}
