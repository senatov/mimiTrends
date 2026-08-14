package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchScorePresentationTest {
    @Test fun `formats every score on the ten point watch scale`() {
        val score = WatchScorePresentation.calculate(TestScanResult.create())

        assertTrue(score.value in 1..10)
        assertTrue(score.label.endsWith("${score.value * 10}%"))
    }

    @Test fun `unconfirmed bottom is never presented as a buy`() {
        val score = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Oversold decline ↓ · watch · bottom unconfirmed")
        )

        assertTrue(score.value <= 3)
        assertTrue(score.label.startsWith("AVOID"))
    }

    @Test fun `penalizes an extended entry without discarding a strong instrument`() {
        val normal = WatchScorePresentation.calculate(TestScanResult.create(signalSource = "Steady rise ↑"))
        val extended = WatchScorePresentation.calculate(
            TestScanResult.create(signalSource = "Steady rise ↑ · extended · wait for pullback")
        )

        assertTrue(extended.value < normal.value)
        assertTrue(extended.value >= 4)
    }

    @Test fun `uses a separate traffic light color scale`() {
        val weak = WatchScore(3, "#b23b48", "")
        val medium = WatchScore(5, "#b26012", "")
        val strong = WatchScore(8, "#137b50", "")

        assertEquals(listOf("#b23b48", "#b26012", "#137b50"), listOf(weak.color, medium.color, strong.color))
    }
}
