package org.senatov.mimitrends.scanner

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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanCyclePresentationTest {
    @Test
    fun `keeps scan starts on the configured cadence`() {
        assertEquals(120_000L, ScanCyclePresentation.nextDelayMillis(180, 60_000))
        assertEquals(1_000L, ScanCyclePresentation.nextDelayMillis(180, 220_000))
    }

    @Test
    fun `summarizes source coverage and freshness`() {
        val batch = ScannerBatchResult(emptyList(), 0, 0, emptyList(), mapOf("FINNHUB" to 7, "YAHOO" to 3), 125)

        val text = ScanCyclePresentation.diagnostics(batch, 12_500)

        assertTrue("12.5s" in text)
        assertTrue("oldest 2m" in text)
        assertTrue("FINNHUB 7" in text)
    }
}