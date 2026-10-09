package org.senatov.mimitrends.providers

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.marketdata.ProviderHttpException
import org.senatov.mimitrends.marketdata.ProviderResponseException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderBackoffTest {
    @Test
    fun `honors provider retry delay and resets after success`() {
        val backoff = ProviderBackoff()
        val now = 1_000_000L

        val delay = backoff.failure(ProviderHttpException(429, 120_000L, "test"), now)

        assertTrue(delay >= 120_000L)
        assertFalse(backoff.canRequest(now + 119_999L))
        assertTrue(backoff.canRequest(now + delay))
        backoff.success()
        assertTrue(backoff.canRequest(now))
    }

    @Test
    fun `jitter remains within twenty percent`() {
        val delays = List(100) { ProviderBackoff().jitteredDelay(1_000L) }

        assertTrue(delays.all { it in 800L..1_200L })
        assertTrue(delays.distinct().size > 1)
    }

    @Test
    fun `invalid provider response pauses requests longer than a transient failure`() {
        val backoff = ProviderBackoff()
        val now = 1_000_000L

        assertEquals(60_000L, backoff.failure(ProviderResponseException("Euronext quote"), now))
        assertFalse(backoff.canRequest(now + 59_999L))
        assertEquals(1L, backoff.remainingMillis(now + 59_999L))
        assertEquals(120_000L, backoff.failure(ProviderResponseException("Euronext quote"), now + 60_000L))
    }
}
