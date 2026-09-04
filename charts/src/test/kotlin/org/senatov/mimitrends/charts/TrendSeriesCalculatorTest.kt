package org.senatov.mimitrends.charts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TrendSeriesCalculatorTest {
    @Test
    fun `ema follows prices with period smoothing`() {
        assertEquals(
            listOf(10.0, 11.0, 12.5),
            TrendSeriesCalculator.ema(listOf(10.0, 12.0, 14.0), 3)
        )
    }

    @Test
    fun `linear regression preserves a straight trend`() {
        assertEquals(
            listOf(10.0, 12.0, 14.0, 16.0),
            TrendSeriesCalculator.linearRegression(listOf(10.0, 12.0, 14.0, 16.0))
        )
    }

    @Test
    fun `empty series remain empty`() {
        assertEquals(emptyList(), TrendSeriesCalculator.ema(emptyList(), 9))
        assertEquals(emptyList(), TrendSeriesCalculator.linearRegression(emptyList()))
    }
}