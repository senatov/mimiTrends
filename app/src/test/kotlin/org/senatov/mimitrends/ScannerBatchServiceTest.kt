@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ScannerCriteria
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScannerBatchServiceTest {
    @Test fun `persists and completes an evaluated batch`() {
        val path = Files.createTempDirectory("mimitrends-batch").resolve("test.db")
        val repository = MarketRepository(path)
        val analytics = AnalyticsRepository(path)
        val service = ScannerBatchService(
            { symbol, _ -> ScanEvaluation(TestScanResult.create(symbol = symbol), emptyList()) },
            analytics, repository, { "TEST" }
        )

        val result = service.execute(listOf("ONE", "TWO"), ScannerCriteria(
            minimumTableResults = 1, resultLimit = 2
        ), { true }, { _, _ -> })

        assertNotNull(result)
        assertEquals(2, result.active.size)
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
}
