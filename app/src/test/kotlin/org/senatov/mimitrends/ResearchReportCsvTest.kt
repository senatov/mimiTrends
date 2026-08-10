package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.WalkForwardMetric
import org.senatov.mimitrends.db.WalkForwardResearchReport
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchReportCsvTest {
    @Test fun `exports stable locale independent research rows`() {
        val report = WalkForwardResearchReport(
            horizonMinutes = 10,
            frictionPercent = 0.20,
            outcomeSamples = 42,
            evaluatedSamples = 12,
            metrics = listOf(WalkForwardMetric(
                family = "Impulse, strict",
                direction = -1,
                samples = 12,
                distinctDays = 4,
                predictedWinRate = 0.55,
                actualWinRate = 0.50,
                brierScore = 0.2475,
                averageNetReturnPercent = -0.125
            ))
        )

        val lines = ResearchReportCsv.format(listOf(report)).lines().filter(String::isNotEmpty)

        assertEquals(2, lines.size)
        assertTrue(lines.first().startsWith("horizon_minutes,friction_percent"))
        assertEquals("10,0.20000000,\"Impulse, strict\",down,12,4,0.55000000,0.50000000,0.24750000,-0.12500000",
            lines.last())
    }

    @Test fun `exports headers when report has no evaluated metrics`() {
        val report = WalkForwardResearchReport(5, 0.20, 3, 0, emptyList())

        assertEquals(1, ResearchReportCsv.format(listOf(report)).lines().count(String::isNotEmpty))
    }
}
