package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.marketdata.ProviderHttpException
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
}
