package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradegatePollingServiceTest {
    @Test
    fun `uses Berlin weekday trading session`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-tradegate-hours").resolve("test.db"))
        val service = TradegatePollingService(repository)

        assertFalse(service.isTradingSession(Instant.parse("2026-08-04T05:59:59Z")))
        assertTrue(service.isTradingSession(Instant.parse("2026-08-04T06:00:00Z")))
        assertFalse(service.isTradingSession(Instant.parse("2026-08-04T20:00:00Z")))
        assertFalse(service.isTradingSession(Instant.parse("2026-08-08T10:00:00Z")))

        service.close()
        repository.close()
    }
}
