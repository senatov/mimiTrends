package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalMetricPresentationTest {
    @Test fun `high anomaly is not presented as a trading recommendation`() {
        val metric = SignalMetricPresentation.strength(TestScanResult.create(anomalyScore = 4.2))

        assertEquals("High", metric.label)
        assertTrue(metric.details.contains("not a buy/sell recommendation"))
        assertTrue(metric.details.contains("does not predict direction"))
    }

    @Test fun `outcome combines net return probability and uncertainty`() {
        val result = TestScanResult.create().copy(
            continuationProbability = 0.58,
            calibrationSamples = 67,
            continuationLowerBound = 0.46,
            continuationUpperBound = 0.69,
            medianNetReturnPercent = 0.08,
            lowerQuartileNetReturnPercent = -0.24,
            upperQuartileNetReturnPercent = 0.31,
            medianFavorableExcursionPercent = 0.42,
            medianAdverseExcursionPercent = -0.27
        )

        val metric = SignalMetricPresentation.outcome(result)

        assertEquals("+0.08% · 58%", metric.label)
        assertTrue(metric.details.contains("95% interval 46–69%"))
        assertTrue(metric.details.contains("Median adverse excursion: -0.27%"))
    }

    @Test fun `labels missing impulse volume as unavailable`() {
        val result = TestScanResult.create(signalSource = "Impulse ↓")

        assertEquals("Unavailable", SignalMetricPresentation.volume(result).label)
    }

    @Test fun `labels a trend without candle volume metrics as price led`() {
        val result = TestScanResult.create(signalSource = "Trend ↑")

        assertEquals("Price-led", SignalMetricPresentation.volume(result).label)
    }

    @Test fun `labels early momentum without reliable volume as unavailable`() {
        val result = TestScanResult.create(signalSource = "Momentum 3m ↑")

        assertEquals("Unavailable", SignalMetricPresentation.volume(result).label)
    }

    @Test fun `describes a statistically rare candle without promising strength`() {
        val result = TestScanResult.create(anomalyScore = 2.8)

        assertEquals("Rare impulse ↑", SignalMetricPresentation.priceAction(result).label)
    }

    @Test fun `presents an extended rise as a wait state`() {
        val result = TestScanResult.create(signalSource = "Steady rise ↑ · extended · wait for pullback")

        val metric = SignalMetricPresentation.priceAction(result)

        assertEquals("Wait for pullback", metric.label)
        assertTrue(metric.details.contains("not a buy signal"))
    }
}
