package org.senatov.mimitrends.market

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.senatov.mimitrends.marketdata.TraderFoxEquity
import org.senatov.mimitrends.marketdata.TraderFoxList
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeeklyMarketUniverseTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `refreshes once a week and reloads the last valid snapshot`() {
        var now = 1_800_000_000_000L
        var loads = 0
        val source = { list: TraderFoxList ->
            loads++
            equities(list, now)
        }
        val path = directory.resolve("weekly.properties")
        WeeklyMarketUniverse(source, path, { now }).use { universe ->
            assertTrue(universe.refreshIfDue())
            assertEquals(160, universe.symbols().size)
            assertEquals(40, universe.symbols().count { it.endsWith(".DE") })
            assertFalse(universe.refreshIfDue())
            assertEquals(4, loads)
        }
        WeeklyMarketUniverse(source, path, { now }).use { reloaded ->
            assertEquals(160, reloaded.symbols().size)
            assertFalse(reloaded.refreshIfDue())
            now += Duration.ofDays(7).toMillis()
            assertTrue(reloaded.refreshIfDue())
            assertEquals(8, loads)
        }
    }

    @Test
    fun `rejects an incomplete refresh and retains the cached symbols`() {
        val now = 1_800_000_000_000L
        val path = directory.resolve("weekly.properties")
        WeeklyMarketUniverse({ equities(it, now) }, path, { now }).use { assertTrue(it.refreshIfDue()) }
        WeeklyMarketUniverse({ emptyList() }, path, { now + Duration.ofDays(8).toMillis() }).use { universe ->
            val old = universe.symbols()
            assertTrue(runCatching { universe.refreshIfDue() }.isFailure)
            assertEquals(old, universe.symbols())
        }
    }

    @Test
    fun `selects only recent priced index members`() {
        val now = 1_800_000_000_000L
        val current = now / 1_000
        val dax = listOf(
            TraderFoxEquity("SAP", "EUR", 200.0, 0.5, current),
            TraderFoxEquity("OLD", "EUR", 10.0, 8.0, current - Duration.ofDays(8).seconds)
        )
        val nyse = listOf(
            TraderFoxEquity("JPM", "USD", 100.0, 1.0, current),
            TraderFoxEquity("TINY", "USD", 1.0, 9.0, current)
        )
        val sp500 = listOf(nyse[0], TraderFoxEquity("OTHER", "USD", 100.0, 2.0, current))
        val nasdaq = listOf(TraderFoxEquity("NVDA", "USD", 100.0, 3.0, current))

        WeeklyMarketUniverse(path = directory.resolve("unused.properties"), nowMillis = { now }).use {
            assertEquals(listOf("SAP.DE", "JPM", "NVDA"), it.select(dax, nyse, sp500, nasdaq, now))
        }
    }

    private fun equities(list: TraderFoxList, now: Long): List<TraderFoxEquity> {
        val count = when (list) {
            TraderFoxList.DAX -> 40
            TraderFoxList.NYSE -> 600
            TraderFoxList.SP_500 -> 450
            TraderFoxList.NASDAQ_100 -> 100
        }
        return (1..count).map { index ->
            val symbol = when (list) {
                TraderFoxList.DAX -> "D$index"
                TraderFoxList.NASDAQ_100 -> "Q$index"
                else -> "N$index"
            }
            TraderFoxEquity(
                symbol, if (list == TraderFoxList.DAX) "EUR" else "USD",
                20.0, index.toDouble() / 100, now / 1_000
            )
        }
    }
}