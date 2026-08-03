package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScanResult
import kotlin.test.assertEquals

class SignalMetricPresentationTest {
    @Test fun `labels missing impulse volume as unavailable`() {
        val result = result(signalSource = "Impulse ↓")

        assertEquals("Unavailable", SignalMetricPresentation.volume(result).label)
    }

    @Test fun `labels a trend without candle volume metrics as price led`() {
        val result = result(signalSource = "Trend ↑")

        assertEquals("Price-led", SignalMetricPresentation.volume(result).label)
    }

    private fun result(signalSource: String) = ScanResult(
        symbol = "TEST",
        price = 100.0,
        anomalyScore = 4.0,
        priceAnomaly = 5.0,
        volumeAnomaly = Double.NaN,
        rangeAnomaly = 5.0,
        relativeVolume = Double.NaN,
        candleBodyRatio = 0.8,
        windowChangePercent = -4.0,
        windowVolume = 0.0,
        sessionVolume = 1_000.0,
        sessionTurnover = 100_000.0,
        signalAgeMinutes = 0,
        signalSource = signalSource,
        updatedAtMillis = 0L
    )
}
