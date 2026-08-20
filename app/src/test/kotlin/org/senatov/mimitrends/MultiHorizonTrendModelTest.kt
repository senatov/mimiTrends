package org.senatov.mimitrends

import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.math.abs

class MultiHorizonTrendModelTest {
    @Test fun `stable multi-month rise scores above a choppy path`() {
        val rising = prices(260) { index -> 100.0 * exp(index * 0.0015) * (1.0 + (index % 5 - 2) * 0.0005) }
        val choppy = prices(260) { index -> 100.0 + (index % 12 - 6) * 0.7 + index * 0.01 }

        val risingScore = assertNotNull(MultiHorizonTrendModel.assess(rising))
        val choppyScore = assertNotNull(MultiHorizonTrendModel.assess(choppy))

        assertTrue(risingScore.score >= 70)
        assertTrue(risingScore.score > choppyScore.score)
        assertTrue(risingScore.confidence >= 90)
        assertTrue(risingScore.details.contains("1y"))
    }

    @Test fun `persistent decline is rejected despite a late bounce`() {
        val prices = prices(130) { index ->
            val decline = 140.0 * exp(-index * 0.002)
            if (index > 125) decline * (1.0 + (index - 125) * 0.012) else decline
        }

        val assessment = assertNotNull(MultiHorizonTrendModel.assess(prices))

        assertTrue(assessment.score < 56)
    }

    @Test fun `short history produces limited confidence without inventing long horizons`() {
        val assessment = assertNotNull(MultiHorizonTrendModel.assess(prices(8) { 100.0 + it }))

        assertTrue(assessment.confidence < 50)
        assertTrue(!assessment.details.contains("1m"))
    }

    @Test fun `one additional session does not make a stable trend score jump`() {
        val initial = assertNotNull(MultiHorizonTrendModel.assess(prices(90) { 80.0 * exp(it * 0.0012) }))
        val updated = assertNotNull(MultiHorizonTrendModel.assess(prices(91) { 80.0 * exp(it * 0.0012) }))

        assertTrue(abs(initial.score - updated.score) <= 3)
    }

    private fun prices(days: Int, close: (Int) -> Double): List<TrendPrice> =
        (0 until days).map { index -> TrendPrice(index * 86_400L, close(index)) }
}
