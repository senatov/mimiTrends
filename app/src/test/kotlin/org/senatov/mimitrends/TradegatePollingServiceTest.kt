package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ProviderInstrument
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun `reuses a verified provider isin before searching by company name`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-tradegate-isin").resolve("test.db"))
        repository.upsertProviderInstrument(ProviderInstrument(
            "EURONEXT", "NOKIA.HE", "FI0009000681", "ETLX", "EUR", "NOKIA CORPORATION"
        ))
        repository.upsertProviderInstrument(ProviderInstrument(
            "LANG_SCHWARZ", "NOKIA.HE", "41540", "LSSI", "EUR", "NOKIA CORP."
        ))
        val service = TradegatePollingService(repository)

        assertEquals("FI0009000681", service.knownIsin("NOKIA.HE")?.identifier)

        service.close()
        repository.close()
    }

    @Test
    fun `does not reuse an Euronext index as an equity isin`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-tradegate-index").resolve("test.db"))
        repository.upsertProviderInstrument(ProviderInstrument(
            "EURONEXT", "ISP.MI", "FRIX00006976", "XPAR", "EUR", "EN G IN190525 D034"
        ))
        val service = TradegatePollingService(repository)

        assertNull(service.knownIsin("ISP.MI"))

        service.close()
        repository.close()
    }
}
