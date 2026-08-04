package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EuronextPollingServiceTest {
    @Test
    fun `uses broad Paris weekday trading session`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-euronext-hours").resolve("test.db"))
        val service = EuronextPollingService(repository)

        assertFalse(service.isTradingSession(Instant.parse("2026-08-04T04:59:59Z")))
        assertTrue(service.isTradingSession(Instant.parse("2026-08-04T05:00:00Z")))
        assertFalse(service.isTradingSession(Instant.parse("2026-08-04T20:00:00Z")))
        assertFalse(service.isTradingSession(Instant.parse("2026-08-08T10:00:00Z")))

        service.close()
        repository.close()
    }
}
