package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandidateQualityGateTest {
    @Test fun `rejects an impulse without volume unless price evidence is exceptional`() {
        val weak = TestScanResult.create().copy(
            symbol = "TEST.DE", windowChangePercent = 0.60, priceAnomaly = 4.0,
            rangeAnomaly = 4.1, volumeAnomaly = Double.NaN, relativeVolume = Double.NaN
        )
        val exceptional = weak.copy(windowChangePercent = 0.80, priceAnomaly = 4.6)

        assertFalse(CandidateQualityGate.qualifies(weak, adaptive = false))
        assertTrue(CandidateQualityGate.qualifies(exceptional, adaptive = false))
    }

    @Test fun `requires a mature efficient trend`() {
        val young = TestScanResult.create(signalSource = "Steady rise ↑").copy(
            signalWindowLabel = "10m steady", windowChangePercent = 0.5, candleBodyRatio = 0.5
        )
        val mature = young.copy(signalWindowLabel = "15m steady")

        assertFalse(CandidateQualityGate.qualifies(young, adaptive = true))
        assertTrue(CandidateQualityGate.qualifies(mature.copy(anomalyScore = 3.25), adaptive = true))
    }

    @Test fun `requires the eightieth percentile for a calibrated adaptive signal`() {
        val result = TestScanResult.create(anomalyScore = 9.0, signalSource = "Impulse ↑ · relaxed")

        assertFalse(CandidateQualityGate.qualifies(result.copy(rankingPercentile = 7.9), adaptive = true))
        assertTrue(CandidateQualityGate.qualifies(result.copy(rankingPercentile = 8.0), adaptive = true))
    }

    @Test fun `rejects cooling and old signals`() {
        assertFalse(CandidateQualityGate.qualifies(
            TestScanResult.create(signalSource = "Impulse ↑ · cooling"), adaptive = false
        ))
        assertFalse(CandidateQualityGate.qualifies(
            TestScanResult.create().copy(signalAgeMinutes = 3), adaptive = false
        ))
    }

    @Test fun `places a sharp downside watch after a long candidate`() {
        val long = TestScanResult.create(symbol = "LONG")
        val downside = TestScanResult.create(anomalyScore = 12.0, signalSource = "Impulse ↓", symbol = "DROP")

        assertTrue(CandidateQualityGate.priorityTier(long) < CandidateQualityGate.priorityTier(downside))
    }

    @Test fun `accepts a weaker current trend only for the watch tier`() {
        val watch = TestScanResult.create(anomalyScore = 2.8, signalSource = "Steady rise ↑ · watch")
            .copy(signalWindowLabel = "10m steady", windowChangePercent = 0.30, candleBodyRatio = 0.18)

        assertFalse(CandidateQualityGate.qualifies(watch, adaptive = false))
        assertTrue(CandidateQualityGate.qualifiesWatch(watch))
    }

    @Test fun `accepts an early recovery only for the watch tier`() {
        val recovery = TestScanResult.create(anomalyScore = 3.0, signalSource = "Early recovery ↑ · watch")
            .copy(signalWindowLabel = "12m recovery", windowChangePercent = 0.45, candleBodyRatio = 0.30)

        assertFalse(CandidateQualityGate.qualifies(recovery, adaptive = false))
        assertTrue(CandidateQualityGate.qualifiesWatch(recovery))
    }

    @Test fun `does not present an extended trend as an active entry`() {
        val extended = TestScanResult.create(
            anomalyScore = 4.0, signalSource = "Steady rise ↑ · extended · wait for pullback"
        ).copy(signalWindowLabel = "60m steady", windowChangePercent = 1.2, candleBodyRatio = 0.45)

        assertFalse(CandidateQualityGate.qualifies(extended, adaptive = true))
        assertTrue(CandidateQualityGate.qualifiesWatch(extended))
    }
}
