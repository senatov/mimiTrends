package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

internal object TestScanResult {
    fun create(anomalyScore: Double = 4.0, signalSource: String = "Impulse ↑") = ScanResult(
        symbol = "TEST",
        price = 100.0,
        anomalyScore = anomalyScore,
        priceAnomaly = 5.0,
        volumeAnomaly = Double.NaN,
        rangeAnomaly = 5.0,
        relativeVolume = Double.NaN,
        candleBodyRatio = 0.8,
        windowChangePercent = 4.0,
        windowVolume = 0.0,
        sessionVolume = 1_000.0,
        sessionTurnover = 100_000.0,
        signalAgeMinutes = 0,
        signalSource = signalSource,
        updatedAtMillis = 0L
    )
}
