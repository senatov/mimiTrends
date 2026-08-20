package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatingPriceCycleDetectorTest {
    @Test fun `detects a repeated three minute price cycle`() {
        val prices = List(8) { listOf(100.0, 101.0, 99.5) }.flatten()

        assertTrue(RepeatingPriceCycleDetector.strength(bars(prices)).isFinite())
    }

    @Test fun `does not mark a directional rise as a cycle`() {
        val prices = List(24) { 100.0 + it * 0.2 }

        assertFalse(RepeatingPriceCycleDetector.strength(bars(prices)).isFinite())
    }

    private fun bars(prices: List<Double>) = prices.mapIndexed { index, price ->
        MinuteBar("TEST", index * 60L, price, price, price, price, 100.0)
    }
}
