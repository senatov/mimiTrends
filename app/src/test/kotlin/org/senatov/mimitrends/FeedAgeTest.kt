package org.senatov.mimitrends

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedAgeTest {
    @Test fun `reports the age of a delayed market data feed`() {
        val result = TestScanResult.create().copy(updatedAtMillis = 1_000_000L)

        assertEquals(15, result.withFeedAge(1_900L).signalAgeMinutes)
    }

    @Test fun `does not erase an older candle signal age`() {
        val result = TestScanResult.create().copy(updatedAtMillis = 1_000_000L, signalAgeMinutes = 20)

        assertEquals(20, result.withFeedAge(1_900L).signalAgeMinutes)
    }
}
