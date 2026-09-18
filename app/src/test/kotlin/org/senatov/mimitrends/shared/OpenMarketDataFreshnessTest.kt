package org.senatov.mimitrends.shared

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

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
