package org.senatov.mimitrends.services

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.scanner.DynamicMarketUniverse
import org.senatov.mimitrends.ui.TableSearchSuggestion
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserWatchlistControllerTest {
    @Test
    fun `looks beyond startup catalog and tracks selected symbol`() {
        val path = Files.createTempDirectory("mimitrends-watchlist-search").resolve("market.db")
        MarketRepository(path).use { repository ->
            repository.upsertCompanyProfile(CompanyProfile("AAPL", "Apple", "NMS", null))
            repository.flushPending()
            val universe = DynamicMarketUniverse()
            var remoteQueries = 0
            var scanStarts = 0
            val controller = UserWatchlistController(
                repository, universe, { scanStarts++ },
                { query ->
                    remoteQueries++
                    if (query == "Infineon") listOf(TableSearchSuggestion("IFX.DE", "Infineon", "GER"))
                    else emptyList()
                },
                Executor(Runnable::run)
            )

            assertEquals("AAPL", controller.actions.search("Apple").join().single().symbol)
            assertEquals(0, remoteQueries)
            val suggestion = controller.actions.search("Infineon").join().single()
            controller.actions.add(suggestion.symbol)

            assertEquals(1, remoteQueries)
            assertEquals(1, scanStarts)
            assertEquals(setOf("IFX.DE"), repository.loadUserWatchlist())
            assertTrue("IFX.DE" in universe.select(ScannerCriteria(symbols = emptyList())).symbols)
        }
    }
}
