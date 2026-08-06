package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScanResult
import kotlin.test.assertEquals

class MarketObservationOverlayTest {
    @Test
    fun `fresh provider observation survives a later stale scan result`() {
        val overlay = MarketObservationOverlay()
        overlay.record("BNP.PA", 114.25, 12_000L, "EURONEXT")

        val result = overlay.apply(result(updatedAtMillis = 9_000L))

        assertEquals(114.25, result.price)
        assertEquals(12_000L, result.updatedAtMillis)
        assertEquals("EURONEXT", result.dataStatus)
    }

    @Test
    fun `older provider observation cannot replace a newer scan result`() {
        val overlay = MarketObservationOverlay()
        overlay.record("BNP.PA", 110.0, 9_000L, "EURONEXT")

        val result = overlay.apply(result(updatedAtMillis = 12_000L))

        assertEquals(113.18, result.price)
        assertEquals(12_000L, result.updatedAtMillis)
        assertEquals("DELAYED 15m", result.dataStatus)
    }

    private fun result(updatedAtMillis: Long) = ScanResult(
        symbol = "BNP.PA", price = 113.18, anomalyScore = 1.0, priceAnomaly = 1.0,
        volumeAnomaly = 1.0, rangeAnomaly = 1.0, relativeVolume = 1.0, candleBodyRatio = 1.0,
        windowChangePercent = 1.0, windowVolume = 1.0, sessionVolume = 1.0, sessionTurnover = 1.0,
        signalAgeMinutes = 1, signalSource = "Impulse ↑", updatedAtMillis = updatedAtMillis,
        dataStatus = "DELAYED 15m"
    )
}
