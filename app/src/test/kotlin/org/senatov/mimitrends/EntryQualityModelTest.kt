package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntryQualityModelTest {
    @Test fun `calm pullback with tight spread has good entry quality`() {
        val assessment = EntryQualityModel.assess(input(
            price = 99.98, bid = 99.97, ask = 100.00,
            one = -0.02, three = 0.04, five = 0.08,
            volatility = 0.18, vwap = 0.10, high = -0.55
        ))

        assertTrue(assessment.score >= 72)
        assertEquals(0, assessment.cooldownMinutes)
        assertEquals("Good entry", assessment.label)
    }

    @Test fun `accelerating purchase near high triggers pullback cooldown`() {
        val assessment = EntryQualityModel.assess(input(
            price = 184.94, bid = 184.80, ask = 184.94,
            one = 0.34, three = 0.82, five = 1.18,
            volatility = 0.22, vwap = 1.35, high = -0.02
        ))

        assertTrue(assessment.score <= 45)
        assertTrue(assessment.cooldownMinutes >= 3)
        assertEquals("Wait for pullback", assessment.label)
    }

    @Test fun `missing quote lowers confidence without inventing a spread`() {
        val assessment = EntryQualityModel.assess(input(
            price = 100.0, bid = Double.NaN, ask = Double.NaN,
            one = 0.0, three = 0.0, five = 0.0,
            volatility = 0.2, vwap = 0.0, high = -0.4
        ))

        assertTrue(assessment.confidence < 100)
        assertTrue(assessment.details.contains("Spread: n/a"))
    }

    private fun input(
        price: Double,
        bid: Double,
        ask: Double,
        one: Double,
        three: Double,
        five: Double,
        volatility: Double,
        vwap: Double,
        high: Double
    ) = EntryQualityInput(price, bid, ask, one, three, five, volatility, vwap, high)
}
