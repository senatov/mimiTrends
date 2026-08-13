@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ScannerCriteria
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerBatchServiceTest {
    @Test fun `persists and completes an evaluated batch`() {
        val path = Files.createTempDirectory("mimitrends-batch").resolve("test.db")
        val repository = MarketRepository(path)
        val analytics = AnalyticsRepository(path)
        val service = ScannerBatchService(
            { symbol, _ -> ScanEvaluation(TestScanResult.create(symbol = symbol), emptyList(),
                sourceStatus = "YAHOO", latestDataEpochSeconds = Instant.now().epochSecond - 90) },
            analytics, repository, { "TEST" }
        )

        val result = service.execute(listOf("ONE", "TWO"), ScannerCriteria(
            minimumTableResults = 1, resultLimit = 2
        ), { true }, { _, _ -> })

        assertNotNull(result)
        assertEquals(2, result.active.size)
        assertEquals(mapOf("YAHOO" to 2), result.sourceCoverage)
        assertTrue(requireNotNull(result.oldestDataAgeSeconds) >= 90)
        analytics.close()
        repository.close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT status, evaluated_symbols, published_symbols FROM scan_runs"
            ).use { row ->
                row.next()
                assertEquals("COMPLETE", row.getString(1))
                assertEquals(2, row.getInt(2))
                assertEquals(2, row.getInt(3))
            }
        }
    }

    @Test fun `marks a cancelled batch as aborted`() {
        val path = Files.createTempDirectory("mimitrends-cancelled-batch").resolve("test.db")
        val repository = MarketRepository(path)
        val analytics = AnalyticsRepository(path)
        val service = ScannerBatchService(
            { _, _ -> error("must not evaluate") }, analytics, repository, { "TEST" }
        )

        assertNull(service.execute(listOf("TEST"), ScannerCriteria(), { false }, { _, _ -> }))

        analytics.close()
        repository.close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().executeQuery("SELECT status FROM scan_runs").use { row ->
                row.next()
                assertEquals("ABORTED", row.getString(1))
            }
        }
    }

    @Test fun `persists the detector rejection reason`() {
        val path = Files.createTempDirectory("mimitrends-rejected-batch").resolve("test.db")
        val repository = MarketRepository(path)
        val analytics = AnalyticsRepository(path)
        val service = ScannerBatchService(
            { _, _ -> ScanEvaluation(null, emptyList(), "NO_HIGHER_LOW") },
            analytics, repository, { "TEST" }
        )

        service.execute(listOf("TEST"), ScannerCriteria(), { true }, { _, _ -> })

        analytics.close()
        repository.close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().executeQuery("SELECT rejection_reason FROM scan_candidates").use { row ->
                row.next()
                assertEquals("NO_HIGHER_LOW", row.getString(1))
            }
        }
    }
}
