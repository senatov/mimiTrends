@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.ScanResult
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchDatasetTest {
    @Test fun `historical backfill is idempotent`() {
        val path = Files.createTempDirectory("mimitrends-research-backfill").resolve("test.db")
        val epoch = 1_800_000_000L
        val outcome = ResearchBackfillOutcome(10, 101.0, 1.0, 10.0, 1.2, -0.2, epoch + 600L)
        AnalyticsRepository(path).use { analytics ->
            val sample = ResearchBackfillSample(result("TEST", epoch), features(epoch), listOf(outcome))
            repeat(2) { analytics.recordResearchBackfill("TEST", listOf(sample)) }
        }

        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM research_samples").use { row ->
                    row.next(); assertEquals(1, row.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM research_outcomes").use { row ->
                    row.next(); assertEquals(1, row.getInt(1))
                }
            }
        }
    }

    @Test fun `records rejected controls and their future outcomes`() {
        val path = Files.createTempDirectory("mimitrends-research-control").resolve("test.db")
        AnalyticsRepository(path).use { analytics ->
            val epoch = 1_800_000_000L
            val run = analytics.beginScan("US", 1, 180)
            analytics.recordScanCandidate(run, "TEST", null, "NO_CURRENT_SIGNAL", "TEST", features(epoch))
            analytics.recordSignalOutcomes("TEST", 101.0, epoch + 10 * 60L, 101.2, 99.8)
        }

        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().executeQuery("""SELECT s.family, s.accepted, o.return_percent
                FROM research_samples s JOIN research_outcomes o ON o.sample_id=s.id
                WHERE o.horizon_minutes=10""").use { row ->
                assertTrue(row.next())
                assertEquals("Control", row.getString(1))
                assertEquals(0, row.getInt(2))
                assertEquals(1.0, row.getDouble(3), 0.000_001)
            }
        }
    }

    @Test fun `walk forward report trains only on earlier days`() {
        val path = Files.createTempDirectory("mimitrends-walk-forward").resolve("test.db")
        AnalyticsRepository(path).use { analytics ->
            val trainingEpoch = 1_800_000_000L
            repeat(20) { index ->
                val symbol = "TRAIN$index"
                val run = analytics.beginScan("US", 1, 180)
                analytics.recordScanCandidate(run, symbol, result(symbol, trainingEpoch), null, "TEST", features(trainingEpoch))
                val outcomePrice = if (index < 12) 101.0 else 99.0
                analytics.recordSignalOutcomes(symbol, outcomePrice, trainingEpoch + 10 * 60L)
            }
            val testEpoch = trainingEpoch + 86_400L
            val run = analytics.beginScan("US", 1, 180)
            analytics.recordScanCandidate(run, "TEST", result("TEST", testEpoch), null, "TEST", features(testEpoch))
            analytics.recordSignalOutcomes("TEST", 101.0, testEpoch + 10 * 60L)

            val report = analytics.walkForwardResearchReport(10, frictionPercent = 0.0)
            val impulse = report.metrics.single { it.family == "Impulse" }
            assertEquals(21, report.outcomeSamples)
            assertEquals(1, report.evaluatedSamples)
            assertEquals(1, impulse.samples)
            assertEquals(13.0 / 22.0, impulse.predictedWinRate, 0.000_001)
            assertEquals(1.0, impulse.actualWinRate, 0.000_001)
        }
    }

    private fun features(epoch: Long) = ResearchFeatures(
        epoch, 100.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6,
        0.7, 0.1, 0.2, -0.3, 1.0, 1.2, 0.8
    )

    private fun result(symbol: String, epoch: Long) = ScanResult(
        symbol, 100.0, 4.0, 4.0, 2.0, 3.0, 2.0, 0.8,
        0.5, 1_000.0, 10_000.0, 1_000_000.0, 0, "Impulse ↑",
        epoch * 1_000L, "TEST", "latest", 100.0, epoch * 1_000L
    )
}
