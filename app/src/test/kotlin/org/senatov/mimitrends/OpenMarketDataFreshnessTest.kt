package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenMarketDataFreshnessTest {
    @Test
    fun `accepts delayed European observations up to twenty minutes`() {
        assertTrue(OpenMarketDataFreshness.isUsable(10_000 - 15 * 60, 10_000))
        assertTrue(OpenMarketDataFreshness.isUsable(10_000 - 20 * 60, 10_000))
    }

    @Test
    fun `rejects previous session and implausibly future observations`() {
        assertFalse(OpenMarketDataFreshness.isUsable(10_000 - 21 * 60, 10_000))
        assertFalse(OpenMarketDataFreshness.isUsable(10_061, 10_000))
        assertFalse(OpenMarketDataFreshness.isUsable(null, 10_000))
    }
}
