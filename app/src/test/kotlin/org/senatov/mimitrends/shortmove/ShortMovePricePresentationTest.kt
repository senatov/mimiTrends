package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ShortMovePricePresentationTest {
    @Test
    fun `shows start and end prices on separate lines`() {
        val move = ShortMove("SAP.DE", -2.5, 123.45, 120.36, 1_000L, 1_300L, 5)

        assertEquals("123.45 →\n120.36", ShortMovePricePresentation.text(move))
    }
}
