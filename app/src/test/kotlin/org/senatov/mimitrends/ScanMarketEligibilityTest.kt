package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScanMarketEligibilityTest {
    private val beforeUsOpen = Instant.parse("2026-08-25T12:00:00Z").toEpochMilli()

    @Test
    fun `includes a US symbol with a fresh extended-hours trade`() {
        assertTrue(ScanMarketEligibility.isActive("INTC", beforeUsOpen - 30_000, beforeUsOpen))
    }

    @Test
    fun `does not wake US scan for a stale extended-hours trade`() {
        assertFalse(ScanMarketEligibility.isActive("INTC", beforeUsOpen - 4 * 60_000, beforeUsOpen))
    }

    @Test
    fun `does not use quote activity to extend European exchange hours`() {
        val afterEuropeanClose = Instant.parse("2026-08-25T19:00:00Z").toEpochMilli()
        assertFalse(
            ScanMarketEligibility.isActive(
                "IFX.DE", afterEuropeanClose - 30_000, afterEuropeanClose
            )
        )
    }
}