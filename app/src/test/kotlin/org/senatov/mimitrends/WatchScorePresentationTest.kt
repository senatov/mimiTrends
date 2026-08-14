package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchScorePresentationTest {
    @Test fun `formats a precise readiness percentage before the category`() {
        val score = WatchScorePresentation.calculate(TestScanResult.create())

        assertTrue(score.value in 0..100)
        assertTrue(score.label.startsWith("${score.value}% ("))
    }

    @Test fun `unconfirmed bottom is never presented as a buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Oversold decline ↓ · watch · bottom unconfirmed")
        )

        assertTrue(score.value <= 29)
        assertTrue(score.label.endsWith("(avoid)"))
    }

    @Test fun `strong rise without volume or outcome confirmation remains wait`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 4.56, signalSource = "Steady rise ↑").copy(
                windowChangePercent = 0.94,
                relativeVolume = Double.NaN,
                volumeAnomaly = Double.NaN,
                continuationProbability = Double.NaN,
                calibrationSamples = 0
            )
        )

        assertTrue(score.value <= 66)
        assertTrue(score.label.endsWith("(wait)"))
    }

    @Test fun `confirmed strong rise can reach buy category`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 4.56, signalSource = "Steady rise ↑").copy(
                windowChangePercent = 0.40,
                relativeVolume = 2.1
            )
        )

        assertTrue(score.value >= 67)
        assertTrue(score.label.endsWith("(buy)"))
        assertTrue(score.value % 10 != 0, "readiness must not be quantized to ten-point steps")
    }

    @Test fun `aging signal cannot remain buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 6.0, signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0,
                signalAgeMinutes = 12
            )
        )

        assertTrue(score.value <= 59)
        assertTrue(score.label.endsWith("(wait)"))
    }

    @Test fun `stale signal is always avoid`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 6.0, signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0,
                signalAgeMinutes = 122
            )
        )

        assertTrue(score.value <= 29)
        assertTrue(score.label.endsWith("(avoid)"))
    }

    @Test fun `penalizes an extended entry without discarding a strong instrument`() {
        val normal = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑").copy(windowChangePercent = 0.4)
        )
        val extended = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑ · extended · wait for pullback")
                .copy(windowChangePercent = 0.4)
        )

        assertTrue(extended.value < normal.value)
        assertTrue(extended.value <= 59)
    }

    @Test fun `uses a separate traffic light color scale`() {
        val weak = WatchScore(20, "#b23b48", "")
        val medium = WatchScore(50, "#b26012", "")
        val strong = WatchScore(80, "#137b50", "")

        assertEquals(listOf("#b23b48", "#b26012", "#137b50"), listOf(weak.color, medium.color, strong.color))
    }
}
