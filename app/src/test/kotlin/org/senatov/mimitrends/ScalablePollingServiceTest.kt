package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.marketdata.ScalableCliUnavailableException
import org.senatov.mimitrends.marketdata.ScalableQuote
import org.senatov.mimitrends.marketdata.ScalableQuoteClient
import org.senatov.mimitrends.model.ProviderInstrument
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScalablePollingServiceTest {
    @Test
    fun `stores authorized scalable quote without activating fallback`() {
        repository().use { repository ->
            repository.upsertProviderInstrument(
                ProviderInstrument(
                    "EURONEXT", "ENR.DE", "DE000ENER6Y0", "XETR", "EUR", "Siemens Energy", 1_000
                )
            )
            val observed = CountDownLatch(1)
            val fallbackValues = mutableListOf<List<String>>()
            val service = ScalablePollingService(
                repository, { observed.countDown() },
                { fallbackValues += it.toList() }, successfulClient()
            )
            service.use { it.replaceSymbols(listOf("ENR.DE")); assertTrue(observed.await(2, TimeUnit.SECONDS)) }

            val quote = repository.loadLatestProviderQuote("ENR.DE", 0)
            assertEquals("SCALABLE", quote?.provider)
            assertEquals(151.23, quote?.last)
            assertTrue(fallbackValues.all { it.isEmpty() })
        }
    }

    @Test
    fun `falls back quietly when scalable access is unavailable`() {
        repository().use { repository ->
            val fallback = CountDownLatch(1)
            val fallbackHistory = mutableListOf<List<String>>()
            val unavailable = object : ScalableQuoteClient {
                override fun verifyAccess() = throw ScalableCliUnavailableException("not authorized")
                override fun loadQuote(isin: String): ScalableQuote = error("not called")
            }
            val service = ScalablePollingService(repository, {}, {
                fallbackHistory += it.toList()
                if (it.isNotEmpty()) fallback.countDown()
            }, unavailable)
            service.use { it.replaceSymbols(listOf("ENR.DE")); assertTrue(fallback.await(2, TimeUnit.SECONDS)) }

            assertTrue(listOf("ENR.DE") in fallbackHistory)
        }
    }

    private fun repository() = MarketRepository(
        Files.createTempDirectory("mimitrends-scalable-provider").resolve("test.db")
    )

    private fun successfulClient() = object : ScalableQuoteClient {
        override fun verifyAccess() = Unit
        override fun loadQuote(isin: String) = ScalableQuote(
            isin, "Siemens Energy", "EUR", 151.23, 151.20, 151.26,
            148.90, System.currentTimeMillis()
        )
    }
}
