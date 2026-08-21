@file:Suppress("SqlNoDataSourceInspection")

package org.senatov.mimitrends.db

import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class UniverseMembershipTest {
    @Test fun `stores regional ranks and dynamic selection source`() {
        val path = Files.createTempDirectory("mimitrends-universe").resolve("test.db")
        AnalyticsRepository(path).use { repository ->
            repository.recordUniverseSelection(
                linkedMapOf("AAPL" to 1, "SAP.DE" to 1, "MSFT" to 2),
                listOf("AAPL", "SAP.DE")
            )
        }

        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().executeQuery(
                "SELECT region, symbol, rank, source FROM universe_membership ORDER BY region, rank"
            ).use { rows ->
                val values = buildList {
                    while (rows.next()) add(listOf(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4)))
                }
                assertEquals(listOf(
                    listOf("EUROPE", "SAP.DE", "1", "WALLSTREET_ONLINE"),
                    listOf("US", "AAPL", "1", "WALLSTREET_ONLINE"),
                    listOf("US", "MSFT", "2", "CONFIGURED_FALLBACK")
                ), values)
            }
        }
    }
}
