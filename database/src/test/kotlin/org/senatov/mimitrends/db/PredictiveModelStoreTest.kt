@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScanResult
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredictiveModelStoreTest {
    @Test fun `activates temporally validated model and enriches current signal`() {
        val path = Files.createTempDirectory("mimitrends-predictive-model").resolve("test.db")
        AnalyticsRepository(path).close()
        seedSeparableHistory(path.toString())

        AnalyticsRepository(path).use { analytics ->
            val results = analytics.trainPredictiveModels()

            assertTrue(results.all { it.status == "ACTIVE" })
            val positive = analytics.withCalibration(result(score = 2.0))
            val negative = analytics.withCalibration(result(score = -2.0))
            assertEquals("LOGISTIC", positive.predictionSource)
            assertTrue(positive.predictionModelVersion > 0)
            assertTrue(positive.predictionSamples >= 300)
            assertTrue(positive.continuationProbability > negative.continuationProbability)
        }
        AnalyticsRepository(path).use { analytics ->
            assertEquals("LOGISTIC", analytics.withCalibration(result(score = 2.0)).predictionSource)
        }
    }

    @Test fun `returns unchanged when no completed outcomes were added`() {
        val path = Files.createTempDirectory("mimitrends-predictive-unchanged").resolve("test.db")
        AnalyticsRepository(path).close()
        seedSeparableHistory(path.toString())
        AnalyticsRepository(path).use { analytics ->
            assertTrue(analytics.trainPredictiveModels().all { it.status == "ACTIVE" })
            assertTrue(analytics.trainPredictiveModels().all { it.status == "UNCHANGED" })
        }
    }

    private fun seedSeparableHistory(path: String) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.autoCommit = false
            connection.prepareStatement("""INSERT INTO research_samples(id, run_id, symbol, observed_epoch,
                entry_price, family, direction, accepted, source, score, jump_z, range_z, volume_z, rvol, return_10m)
                VALUES (?, 0, ?, ?, 100, 'Impulse', 1, 1, 'TEST', ?, 0, 0, 0, 1, 0)""").use { sample ->
                connection.prepareStatement("""INSERT INTO research_outcomes(sample_id, horizon_minutes,
                    observed_price, return_percent, elapsed_minutes, observed_at) VALUES (?, ?, ?, ?, ?, ?)""").use { outcome ->
                    var id = 1L
                    repeat(8) { day -> repeat(50) { index ->
                        val epoch = 1_800_000_000L + day * 86_400L + index * 900L
                        val wins = index % 2 == 0
                        sample.setLong(1, id); sample.setString(2, "S$id"); sample.setLong(3, epoch)
                        sample.setDouble(4, if (wins) 2.0 else -2.0); sample.addBatch()
                        listOf(5, 10, 30).forEach { horizon ->
                            val returnPercent = if (wins) 1.0 else -1.0
                            outcome.setLong(1, id); outcome.setInt(2, horizon)
                            outcome.setDouble(3, 100.0 + returnPercent); outcome.setDouble(4, returnPercent)
                            outcome.setDouble(5, horizon.toDouble()); outcome.setLong(6, epoch + horizon * 60L)
                            outcome.addBatch()
                        }
                        id++
                    } }
                    sample.executeBatch(); outcome.executeBatch()
                }
            }
            connection.commit()
        }
    }

    private fun result(score: Double) = ScanResult(
        symbol = "TEST", price = 100.0, anomalyScore = score, priceAnomaly = 0.0,
        volumeAnomaly = 0.0, rangeAnomaly = 0.0, relativeVolume = 1.0, candleBodyRatio = 0.8,
        windowChangePercent = 0.0, windowVolume = 1_000.0, sessionVolume = 10_000.0,
        sessionTurnover = 1_000_000.0, signalAgeMinutes = 0, signalSource = "Impulse ↑",
        updatedAtMillis = 1_900_000_000_000L
    )
}
