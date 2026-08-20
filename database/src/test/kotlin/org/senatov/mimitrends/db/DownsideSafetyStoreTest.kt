@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownsideSafetyStoreTest {
    @Test fun `calibrates safety from ninety minute maximum adverse excursion`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("""CREATE TABLE research_samples(
                    id INTEGER PRIMARY KEY, symbol TEXT, observed_epoch INTEGER)""")
                statement.execute("""CREATE TABLE research_outcomes(
                    sample_id INTEGER, horizon_minutes INTEGER, minimum_return_percent REAL)""")
                repeat(12) { index ->
                    statement.execute("INSERT INTO research_samples VALUES(${index + 1},'AAPL',${1_700_000_000L + index * 86_400L})")
                    val drawdown = if (index < 9) -0.40 else -1.20
                    statement.execute("INSERT INTO research_outcomes VALUES(${index + 1},90,$drawdown)")
                }
            }

            val result = DownsideSafetyStore(connection).calibration(european = false)

            assertEquals(12, result.samples)
            assertEquals(12, result.distinctDays)
            assertTrue(result.probability > 0.60)
        }
    }
}
