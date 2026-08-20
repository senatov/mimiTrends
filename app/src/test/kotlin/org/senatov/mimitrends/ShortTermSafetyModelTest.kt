package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ResearchFeatures
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertTrue

class ShortTermSafetyModelTest {
    @Test fun `negative acceleration below vwap blocks an Apple-like reversal`() {
        val now = epoch(11, 0)
        val assessment = ShortTermSafetyModel.assess(
            "AAPL", bars(now, falling = true), features(now, -0.18, -0.42, -0.55, -0.28, -0.72),
            entry(score = 43), 82, now
        )

        assertTrue(assessment.score <= 39)
        assertTrue(assessment.label.contains("risk", ignoreCase = true))
    }

    @Test fun `orderly liquid regular-session setup can qualify`() {
        val now = epoch(11, 0)
        val assessment = ShortTermSafetyModel.assess(
            "AAPL", bars(now, falling = false), features(now, 0.03, 0.08, 0.12, 0.10, 0.62),
            entry(score = 82), 68, now
        )

        assertTrue(assessment.score >= 56)
        assertTrue(assessment.confidence >= 50)
    }

    @Test fun `premarket data cannot produce an actionable high score`() {
        val now = epoch(8, 45)
        val assessment = ShortTermSafetyModel.assess(
            "AAPL", bars(now, falling = false), features(now, 0.10, 0.25, 0.40, 0.50, 0.80),
            entry(score = 90), 90, now
        )

        assertTrue(assessment.score <= 49)
        assertTrue(assessment.confidence < 50)
    }

    private fun bars(now: Long, falling: Boolean): List<MinuteBar> = (15 downTo 0).map { age ->
        val progress = 15 - age
        val price = if (falling) 101.0 - progress * 0.06 else 100.0 + progress * 0.02
        MinuteBar("AAPL", now - age * 60L, price, price + 0.03, price - 0.03, price, 10_000.0)
    }

    private fun features(now: Long, one: Double, three: Double, five: Double, vwap: Double, efficiency: Double) =
        ResearchFeatures(now, 100.0, one, three, five, five, five, five, 0.3, 0.15, vwap,
            -0.4, 1.0, 1.2, efficiency)

    private fun entry(score: Int) = EntryQualityAssessment(score, 100, "test", 0, "test")

    private fun epoch(hour: Int, minute: Int): Long = LocalDateTime.of(2026, 8, 20, hour, minute)
        .atZone(ZoneId.of("America/New_York")).toEpochSecond()
}
