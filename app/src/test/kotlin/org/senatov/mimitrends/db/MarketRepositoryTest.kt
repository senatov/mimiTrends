package org.senatov.mimitrends.db

import org.junit.jupiter.api.io.TempDir
import org.senatov.mimitrends.model.Candle
import org.senatov.mimitrends.model.MarketSnapshot
import org.senatov.mimitrends.model.Quote
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarketRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `stores and reloads real market points`() {
        val repository = MarketRepository(directory.resolve("market.db"))
        val now = Instant.now().epochSecond
        repository.save(
            MarketSnapshot(
                symbol = "SAP.DE",
                quote = Quote(200.0, 1.0, 0.5, 202.0, 197.0, 198.0, 199.0),
                candles = listOf(Candle(now - 86_400, 198.0))
            )
        )
        val cached = assertNotNull(repository.load("SAP.DE", 30))
        assertEquals(200.0, cached.quote.current)
        assertTrue(cached.candles.size >= 2)
        assertTrue(cached.fromCache)
    }
}
