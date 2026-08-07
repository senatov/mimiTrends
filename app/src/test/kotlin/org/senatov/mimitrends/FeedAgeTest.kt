package org.senatov.mimitrends

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedAgeTest {
    @Test fun `reports completed minutes without overstating feed delay`() {
        assertEquals(15, FeedFreshness.ageMinutes(1_000_000L, 1_900_001L))
        assertEquals("<1m", FeedFreshness.ageLabel(1_000_000L, 1_059_999L))
        assertEquals("1m", FeedFreshness.ageLabel(1_000_000L, 1_060_000L))
    }

    @Test fun `accepts a feed within its declared delay and grace period`() {
        val now = 2_200_000L

        assertEquals(false, FeedFreshness.isStale(1_000_000L, "DELAYED 15m", now))
        assertEquals("◷", FeedFreshness.icon(1_000_000L, "DELAYED 15m", now))
    }

    @Test fun `marks a feed stale beyond its declared delay and grace period`() {
        val now = 2_260_000L

        assertEquals(true, FeedFreshness.isStale(1_000_000L, "DELAYED 15m", now))
        assertEquals("⚠", FeedFreshness.icon(1_000_000L, "DELAYED 15m", now))
    }

    @Test fun `shows an hourglass while focused data is refreshing`() {
        assertEquals("⌛", FeedFreshness.icon(1_000_000L, FeedFreshness.REFRESHING, 9_000_000L))
        assertEquals(false, FeedFreshness.isStale(1_000_000L, FeedFreshness.REFRESHING, 9_000_000L))
    }
}
