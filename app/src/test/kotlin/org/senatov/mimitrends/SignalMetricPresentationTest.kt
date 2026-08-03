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
}
