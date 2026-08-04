package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ProviderHttpExceptionTest {
    @Test
    fun `parses retry after seconds`() {
        assertEquals(90_000L, ProviderHttpException.parseRetryAfter("90", 0L))
    }

    @Test
    fun `parses retry after HTTP date`() {
        assertEquals(
            120_000L,
            ProviderHttpException.parseRetryAfter("Tue, 04 Aug 2026 10:02:00 GMT", 1_785_837_600_000L)
        )
    }
}
