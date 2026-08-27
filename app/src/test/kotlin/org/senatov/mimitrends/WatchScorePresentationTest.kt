package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchScorePresentationTest {
    @Test fun `poor entry quality prevents a buy despite a positive signal`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 6.0, signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0,
                bidPrice = 100.0,
                askPrice = 100.03,
                entryQualityScore = 28,
                entryQualityConfidence = 100,
                entryQualityLabel = "Wait for pullback",
                entryCooldownMinutes = 6
            )
        )

        assertTrue(score.value <= 29)
        assertEquals("${score.value}%", score.label)
        assertTrue(score.details.contains("Entry quality: 28%"))
    }

    @Test
    fun `formats a precise opportunity percentage without an ambiguous action label`() {
        val score = WatchScorePresentation.calculate(TestScanResult.create())

        assertTrue(score.value in 0..100)
        assertEquals("${score.value}%", score.label)
    }

    @Test fun `unconfirmed bottom is never presented as a buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Oversold decline ↓ · watch · bottom unconfirmed")
        )

        assertTrue(score.value <= 29)
        assertEquals("${score.value}%", score.label)
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
        assertTrue(score.value < 67)
    }

    @Test fun `confirmed strong rise can reach buy category`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 4.56, signalSource = "Steady rise ↑").copy(
                windowChangePercent = 0.40,
                relativeVolume = 2.1,
                bidPrice = 100.00,
                askPrice = 100.04
            )
        )

        assertTrue(score.value >= 67)
        assertEquals("${score.value}%", score.label)
        assertTrue(score.value % 10 != 0, "readiness must not be quantized to ten-point steps")
    }

    @Test fun `strong trend cannot compensate for a late entry`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 6.0, signalSource = "Steady rise ↑").copy(
                windowChangePercent = 1.10,
                relativeVolume = 3.0,
                continuationProbability = 0.70,
                continuationLowerBound = 0.60,
                continuationUpperBound = 0.78,
                calibrationSamples = 40
            )
        )

        assertTrue(score.value <= 49)
        assertTrue(score.value < 67)
    }

    @Test fun `aging signal cannot remain buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 6.0, signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0,
                signalAgeMinutes = 12
            )
        )

        assertTrue(score.value <= 59)
        assertTrue(score.value < 67)
    }

    @Test fun `stale signal is always avoid`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(anomalyScore = 6.0, signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0,
                signalAgeMinutes = 122
            )
        )

        assertTrue(score.value <= 29)
        assertTrue(score.value < 35)
    }

    @Test fun `penalizes an extended entry without discarding a strong instrument`() {
        val normal = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑").copy(
                windowChangePercent = 0.4, relativeVolume = 2.0, bidPrice = 100.0, askPrice = 100.04
            )
        )
        val extended = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑ · extended · wait for pullback")
                .copy(windowChangePercent = 0.4, relativeVolume = 2.0, bidPrice = 100.0, askPrice = 100.04)
        )

        assertTrue(extended.value < normal.value)
        assertTrue(extended.value <= 59)
    }

    @Test fun `missing executable quote cannot produce buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "V-Reversal ↑").copy(relativeVolume = 3.0)
        )

        assertTrue(score.value <= 59)
        assertTrue(score.value < 67)
    }

    @Test fun `spread wider than plausible short move is avoid`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "V-Reversal ↑").copy(
                relativeVolume = 3.0, bidPrice = 121.84, askPrice = 122.50
            )
        )

        assertTrue(score.value <= 29)
        assertTrue(score.value < 35)
    }

    @Test fun `weak calibrated downside cannot be presented as buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0, bidPrice = 100.0, askPrice = 100.04,
                continuationProbability = 0.62, continuationLowerBound = 0.44,
                continuationUpperBound = 0.74, calibrationSamples = 40,
                lowerQuartileNetReturnPercent = -0.08
            )
        )

        assertTrue(score.value <= 59)
        assertTrue(score.value < 67)
    }

    @Test fun `negative latest three minutes override an earlier bullish jump`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0, bidPrice = 100.0, askPrice = 100.04,
                recentThreeMinutePercent = -0.12, recentFiveMinutePercent = -0.06
            )
        )

        assertTrue(score.value <= 29)
        assertTrue(score.value < 35)
    }

    @Test fun `cyclic latest tail cannot remain buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑").copy(
                relativeVolume = 3.0, bidPrice = 100.0, askPrice = 100.04,
                recentThreeMinutePercent = 0.04, recentFiveMinutePercent = 0.06,
                recentDirectionChanges = 4
            )
        )

        assertTrue(score.value <= 49)
        assertTrue(score.value < 67)
    }

    @Test fun `uses a separate traffic light color scale`() {
        val weak = WatchScore(20, "#b23b48", "")
        val medium = WatchScore(50, "#b26012", "")
        val strong = WatchScore(80, "#137b50", "")

        assertEquals(listOf("#b23b48", "#b26012", "#137b50"), listOf(weak.color, medium.color, strong.color))
    }
}