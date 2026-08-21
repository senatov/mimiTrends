package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenMarketDataFreshnessTest {
    @Test
    fun `accepts only observations fresh enough for a current recommendation`() {
        assertTrue(OpenMarketDataFreshness.isUsable(10_000 - 3 * 60, 10_000))
        assertFalse(OpenMarketDataFreshness.isUsable(10_000 - 3 * 60 - 1, 10_000))
    }

    @Test
    fun `rejects previous session and implausibly future observations`() {
        assertFalse(OpenMarketDataFreshness.isUsable(10_000 - 16 * 60, 10_000))
        assertFalse(OpenMarketDataFreshness.isUsable(10_061, 10_000))
        assertFalse(OpenMarketDataFreshness.isUsable(null, 10_000))
    }
}
