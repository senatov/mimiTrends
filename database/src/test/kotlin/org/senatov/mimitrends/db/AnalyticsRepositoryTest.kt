@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyticsRepositoryTest {
    @Test fun `migrates and stores derived analytics and scan history`() {
        val path = Files.createTempDirectory("mimitrends-analytics").resolve("test.db")
        MarketRepository(path).close()
        val analytics = AnalyticsRepository(path)
        analytics.upsertInstrument(InstrumentMetadata("TEST", "Test Corp", "NASDAQ", "USD", "America/New_York"))
        analytics.upsertCorporateAction(CorporateAction("TEST", "SPLIT", 1_000, ratio = 2.0, source = "TEST"))
        analytics.recordFxRate("EUR", "USD", 1.15, "TEST", 86_400)

        val bars = (0 until 180).map { minute ->
            val open = 100.0 + minute * 0.01
            MinuteBar("TEST", 1_800_000_000L + minute * 60L, open, open + 0.2, open - 0.1, open + 0.1, 1_000.0 + minute)
        }
        analytics.refreshDerived("TEST", bars, "TEST")
        analytics.recordDataQuality("TEST", "TEST", "REALTIME", bars.last().minuteEpochSeconds, bars.size)
        assertTrue(analytics.loadAggregatedBars("TEST", 5, 0).isNotEmpty())

        val run = analytics.beginScan("US", 1, 180)
        analytics.recordScanCandidate(run, "TEST", result(), null, "TEST")
        analytics.completeScan(run, listOf("TEST"), 0)
        analytics.recordSignalOutcomes("TEST", 102.0, Instant.now().epochSecond + 301)
        val stats = analytics.stats()
        assertEquals(1, stats.instruments)
        assertEquals(1, stats.scanRuns)
        assertEquals(1, stats.scanCandidates)
        assertTrue(stats.aggregateBars > 0)
        assertTrue(stats.baselines > 0)
        assertTrue(stats.outcomes > 0)
        analytics.close()

        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM corporate_actions").use { it.next(); assertEquals(1, it.getInt(1)) }
                statement.executeQuery("SELECT COUNT(*) FROM trading_sessions").use { it.next(); assertTrue(it.getInt(1) > 0) }
                statement.executeQuery("SELECT COUNT(*) FROM market_calendar_rules").use { it.next(); assertEquals(2, it.getInt(1)) }
                statement.executeQuery("SELECT published FROM scan_candidates").use { it.next(); assertEquals(1, it.getInt(1)) }
            }
        }
        AnalyticsRepository(path).close()
    }

    private fun result() = ScanResult(
        symbol = "TEST", price = 101.0, anomalyScore = 3.0, priceAnomaly = 4.0,
        volumeAnomaly = 2.0, rangeAnomaly = 3.0, relativeVolume = 2.0,
        candleBodyRatio = 0.8, windowChangePercent = 1.2, windowVolume = 1_000.0,
        sessionVolume = 100_000.0, sessionTurnover = 10_000_000.0,
        signalAgeMinutes = 0, signalSource = "Impulse ↑", updatedAtMillis = 1_000,
        dataStatus = "TEST", signalWindowLabel = "latest"
    )
}
