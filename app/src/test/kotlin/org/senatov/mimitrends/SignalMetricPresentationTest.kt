package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SignalMetricPresentationTest {
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
