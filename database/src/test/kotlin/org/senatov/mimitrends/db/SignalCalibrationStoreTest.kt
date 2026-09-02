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
                    accepted INTEGER, published INTEGER, source TEXT, score REAL)""")
                statement.execute("""CREATE TABLE signal_outcomes(
                    run_id INTEGER, symbol TEXT, horizon_minutes INTEGER, return_percent REAL,
                    maximum_return_percent REAL, minimum_return_percent REAL)""")
                repeat(12) { index ->
                    val run = index + 1
                    val symbol = "S$run"
                    val outcome = if (index < 8) 0.5 else -0.5
                    statement.execute("INSERT INTO scan_candidates VALUES($run,'$symbol',${1_000 + index * 86_400},'Impulse ↑',1,1,'CACHE',NULL)")
                    statement.execute("INSERT INTO signal_outcomes VALUES($run,'$symbol',10,$outcome,0.8,-0.3)")
                }
                statement.execute("INSERT INTO scan_candidates VALUES(99,'S1',1300,'Impulse ↑',1,1,'CACHE',NULL)")
                statement.execute("INSERT INTO signal_outcomes VALUES(99,'S1',10,5.0,5.0,-0.1)")
                statement.execute("INSERT INTO scan_candidates VALUES(100,'FUTURE',3000000,'Impulse ↑',1,1,'CACHE',NULL)")
                statement.execute("INSERT INTO signal_outcomes VALUES(100,'FUTURE',10,50.0,50.0,-0.1)")
            }

            val calibrated = SignalCalibrationStore(connection).enrich(result())

            assertEquals(12, calibrated.calibrationSamples)
            assertEquals(9.0 / 14.0, calibrated.continuationProbability, 1e-12)
            assertEquals(0.3, calibrated.medianNetReturnPercent, 1e-12)
            assertEquals(0.8, calibrated.medianFavorableExcursionPercent, 1e-12)
            assertEquals(-0.3, calibrated.medianAdverseExcursionPercent, 1e-12)
            assertTrue(calibrated.continuationLowerBound < calibrated.continuationProbability)
            assertTrue(calibrated.continuationUpperBound > calibrated.continuationProbability)
            assertEquals(0.39062208887279953, calibrated.continuationLowerBound, 1e-12)
            assertEquals(0.8618799089087867, calibrated.continuationUpperBound, 1e-12)
            assertEquals(4.0, calibrated.anomalyScore, 1e-12)
        }
    }

    @Test fun `does not display a probability without completed episodes`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scan_candidates(run_id INTEGER, symbol TEXT, signal_epoch INTEGER, signal TEXT, accepted INTEGER, published INTEGER, source TEXT, score REAL)")
                statement.execute("""CREATE TABLE signal_outcomes(run_id INTEGER, symbol TEXT, horizon_minutes INTEGER,
                    return_percent REAL, maximum_return_percent REAL, minimum_return_percent REAL)""")
            }
            val calibrated = SignalCalibrationStore(connection).enrich(result())
            assertTrue(calibrated.continuationProbability.isNaN())
        }
    }

    @Test fun `provides a smoothed preliminary probability from an incomplete sample`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scan_candidates(run_id INTEGER, symbol TEXT, signal_epoch INTEGER, signal TEXT, accepted INTEGER, published INTEGER, source TEXT, score REAL)")
                statement.execute("""CREATE TABLE signal_outcomes(run_id INTEGER, symbol TEXT, horizon_minutes INTEGER,
                    return_percent REAL, maximum_return_percent REAL, minimum_return_percent REAL)""")
                repeat(3) { index ->
                    statement.execute("INSERT INTO scan_candidates VALUES(${index + 1},'S$index',${1_000 + index},'Impulse ↑',1,1,'CACHE',NULL)")
                    statement.execute("INSERT INTO signal_outcomes VALUES(${index + 1},'S$index',10,0.5,0.8,-0.3)")
                }
            }

            val calibrated = SignalCalibrationStore(connection).enrich(result())

            assertEquals(3, calibrated.calibrationSamples)
            assertEquals(4.0 / 5.0, calibrated.continuationProbability, 1e-12)
            assertTrue(calibrated.medianNetReturnPercent.isNaN())
        }
    }

    @Test fun `marks a concentrated sample as a preliminary probability`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scan_candidates(run_id INTEGER, symbol TEXT, signal_epoch INTEGER, signal TEXT, accepted INTEGER, published INTEGER, source TEXT, score REAL)")
                statement.execute("""CREATE TABLE signal_outcomes(run_id INTEGER, symbol TEXT, horizon_minutes INTEGER,
                    return_percent REAL, maximum_return_percent REAL, minimum_return_percent REAL)""")
                repeat(12) { index ->
                    val epoch = 1_000 + index * 86_400
                    statement.execute("INSERT INTO scan_candidates VALUES(${index + 1},'ONE',$epoch,'Impulse ↑',1,1,'CACHE',NULL)")
                    statement.execute("INSERT INTO signal_outcomes VALUES(${index + 1},'ONE',10,0.5,0.8,-0.3)")
                }
            }

        val calibrated = SignalCalibrationStore(connection).enrich(result())

        assertEquals(12, calibrated.calibrationSamples)
        assertEquals(13.0 / 14.0, calibrated.continuationProbability, 1e-12)
        assertTrue(calibrated.medianNetReturnPercent.isNaN())
        }
    }

    @Test fun `normalizes detector score against prior signals from the same family`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE scan_candidates(run_id INTEGER, symbol TEXT, signal_epoch INTEGER, signal TEXT, accepted INTEGER, published INTEGER, source TEXT, score REAL)")
                statement.execute("""CREATE TABLE signal_outcomes(run_id INTEGER, symbol TEXT, horizon_minutes INTEGER,
                    return_percent REAL, maximum_return_percent REAL, minimum_return_percent REAL)""")
                repeat(30) { index ->
                    statement.execute("INSERT INTO scan_candidates VALUES(${index + 1},'S$index',${index + 1},'Impulse ↑',1,0,'CACHE',$index)")
                }
            }

            val calibrated = SignalCalibrationStore(connection).enrich(result().copy(anomalyScore = 15.0))

            assertEquals(15.0, calibrated.anomalyScore, 1e-12)
            assertEquals(10.0 * 15.5 / 30.0, calibrated.rankingPercentile, 1e-12)
        }
    }

    private fun result() = ScanResult(
        symbol = "TEST", price = 100.0, anomalyScore = 4.0, priceAnomaly = 5.0,
        volumeAnomaly = 2.0, rangeAnomaly = 5.0, relativeVolume = 2.0,
        candleBodyRatio = 0.8, windowChangePercent = 0.5, windowVolume = 1_000.0,
        sessionVolume = 10_000.0, sessionTurnover = 1_000_000.0, signalAgeMinutes = 0,
        signalSource = "Impulse ↑", updatedAtMillis = 2_000_000_000L,
        signalEpochMillis = 2_000_000_000L
    )
}
