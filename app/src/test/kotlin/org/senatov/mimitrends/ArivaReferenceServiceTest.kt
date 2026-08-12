package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.marketdata.ArivaInstrumentReference
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.ProviderInstrument
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArivaReferenceServiceTest {
    @Test fun `adds bounded minute and second jitter to the half hour interval`() {
        assertEquals(25 * 60_000L, ArivaPollingSchedule.nextDelayMillis(-10 * 60_000L))
        assertEquals(35 * 60_000L, ArivaPollingSchedule.nextDelayMillis(10 * 60_000L))
        repeat(100) {
            assertTrue(ArivaPollingSchedule.nextDelayMillis() in 25 * 60_000L..35 * 60_000L)
        }
    }

    @Test fun `persists an ARIVA reference only after verifying the selected isin`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-ariva").resolve("test.db"))
        repository.upsertCompanyProfile(CompanyProfile("CPR.MI", "CAMPARI", "Milan", null, null, 1L))
        repository.upsertProviderInstrument(ProviderInstrument(
            "EURONEXT", "CPR.MI", "NL0015435975", "MTAH", "EUR", "CAMPARI"
        ))
        val requested = mutableListOf<String>()
        val service = ArivaReferenceService(repository) { isin ->
            requested += isin
            ArivaInstrumentReference(isin, "A2P8B7", "https://www.ariva.de/aktien/campari-aktie")
        }

        service.verifySymbol("CPR.MI")

        assertEquals(listOf("NL0015435975"), requested)
        assertEquals("NL0015435975", repository.loadProviderInstrument("ARIVA", "CPR.MI")?.identifier)
        service.close()
        repository.close()
    }

    @Test fun `ignores symbol replacement after close`() {
        val repository = MarketRepository(Files.createTempDirectory("mimitrends-ariva-close").resolve("test.db"))
        val service = ArivaReferenceService(repository)

        service.close()
        service.replaceSymbols(listOf("AAPL"))

        repository.close()
    }
}
