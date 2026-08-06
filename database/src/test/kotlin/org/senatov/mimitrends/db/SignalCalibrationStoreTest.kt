@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScanResult
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalCalibrationStoreTest {
    @Test fun `deduplicates nearby scans into independent episodes`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""CREATE TABLE scan_candidates(
                    run_id INTEGER, symbol TEXT, signal_epoch INTEGER, signal TEXT,
                    accepted INTEGER, published INTEGER)""")
                statement.execute("""CREATE TABLE signal_outcomes(
                    run_id INTEGER, symbol TEXT, horizon_minutes INTEGER, return_percent REAL,
                    maximum_return_percent REAL, minimum_return_percent REAL)""")
                repeat(12) { index ->
                    val run = index + 1
                    val symbol = "S$run"
                    val outcome = if (index < 8) 0.5 else -0.5
                    statement.execute("INSERT INTO scan_candidates VALUES($run,'$symbol',${1_000 + index * 86_400},'Impulse ↑',1,1)")
                    statement.execute("INSERT INTO signal_outcomes VALUES($run,'$symbol',10,$outcome,0.8,-0.3)")
                }
                statement.execute("INSERT INTO scan_candidates VALUES(99,'S1',1300,'Impulse ↑',1,1)")
                statement.execute("INSERT INTO signal_outcomes VALUES(99,'S1',10,5.0,5.0,-0.1)")
            }

            val calibrated = SignalCalibrationStore(connection).enrich(result())

            assertEquals(12, calibrated.calibrationSamples)
            assertEquals(9.0 / 14.0, calibrated.continuationProbability, 1e-12)
            assertEquals(0.3, calibrated.medianNetReturnPercent, 1e-12)
            assertEquals(0.8, calibrated.medianFavorableExcursionPercent, 1e-12)
            assertEquals(-0.3, calibrated.medianAdverseExcursionPercent, 1e-12)
            assertTrue(calibrated.continuationLowerBound < calibrated.continuationProbability)
            assertTrue(calibrated.continuationUpperBound > calibrated.continuationProbability)
            assertEquals(4.0, calibrated.anomalyScore, 1e-12)
        }
    }

    @Test fun `does not display a probability from fewer than twelve episodes`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scan_candidates(run_id INTEGER, symbol TEXT, signal_epoch INTEGER, signal TEXT, accepted INTEGER, published INTEGER)")
                statement.execute("""CREATE TABLE signal_outcomes(run_id INTEGER, symbol TEXT, horizon_minutes INTEGER,
                    return_percent REAL, maximum_return_percent REAL, minimum_return_percent REAL)""")
            }
            val calibrated = SignalCalibrationStore(connection).enrich(result())
            assertTrue(calibrated.continuationProbability.isNaN())
        }
    }

    @Test fun `does not display a probability from a concentrated sample`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scan_candidates(run_id INTEGER, symbol TEXT, signal_epoch INTEGER, signal TEXT, accepted INTEGER, published INTEGER)")
                statement.execute("""CREATE TABLE signal_outcomes(run_id INTEGER, symbol TEXT, horizon_minutes INTEGER,
                    return_percent REAL, maximum_return_percent REAL, minimum_return_percent REAL)""")
                repeat(12) { index ->
                    val epoch = 1_000 + index * 86_400
                    statement.execute("INSERT INTO scan_candidates VALUES(${index + 1},'ONE',$epoch,'Impulse ↑',1,1)")
                    statement.execute("INSERT INTO signal_outcomes VALUES(${index + 1},'ONE',10,0.5,0.8,-0.3)")
                }
            }

            val calibrated = SignalCalibrationStore(connection).enrich(result())

            assertEquals(12, calibrated.calibrationSamples)
            assertTrue(calibrated.continuationProbability.isNaN())
        }
    }

    private fun result() = ScanResult(
        symbol = "TEST", price = 100.0, anomalyScore = 4.0, priceAnomaly = 5.0,
        volumeAnomaly = 2.0, rangeAnomaly = 5.0, relativeVolume = 2.0,
        candleBodyRatio = 0.8, windowChangePercent = 0.5, windowVolume = 1_000.0,
        sessionVolume = 10_000.0, sessionTurnover = 1_000_000.0, signalAgeMinutes = 0,
        signalSource = "Impulse ↑", updatedAtMillis = 1_000_000L
    )
}
