package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentResultDeduplicatorTest {
    @Test fun `keeps the strongest listing when isin is shared`() {
        val deduplicator = InstrumentResultDeduplicator(
            loadIsin = { if (it.startsWith("STLA")) "NL00150001Q9" else null },
            loadCompanyName = { null }
        )
        val primary = TestScanResult.create(symbol = "STLA").copy(anomalyScore = 3.0)
        val german = TestScanResult.create(symbol = "STLA.DE").copy(anomalyScore = 4.0)

        assertEquals(listOf("STLA.DE"), deduplicator.deduplicate(listOf(primary, german)).map { it.symbol })
    }

    @Test fun `uses normalized company name when isin is unavailable`() {
        val names = mapOf("STLA" to "STELLANTIS NV", "STLA.DE" to "STELLANTIS")
        val deduplicator = InstrumentResultDeduplicator({ null }, names::get)
        val newer = TestScanResult.create(symbol = "STLA").copy(updatedAtMillis = 2_000)
        val older = TestScanResult.create(symbol = "STLA.DE").copy(updatedAtMillis = 1_000)

        assertEquals(listOf("STLA"), deduplicator.deduplicate(listOf(older, newer)).map { it.symbol })
    }

    @Test fun `does not merge equal names when known isins conflict`() {
        val isins = mapOf("AAA.DE" to "DE0000000001", "AAA" to "US0000000002")
        val deduplicator = InstrumentResultDeduplicator(isins::get) { "Example SE" }
        val results = listOf(TestScanResult.create(symbol = "AAA.DE"), TestScanResult.create(symbol = "AAA"))

        assertEquals(2, deduplicator.deduplicate(results).size)
    }

    @Test fun `removes transaction-taxed results loaded from history`() {
        val deduplicator = InstrumentResultDeduplicator({ null }, { null })
        val results = listOf(TestScanResult.create(symbol = "TTE.PA"), TestScanResult.create(symbol = "AIR.DE"))

        assertEquals(listOf("AIR.DE"), deduplicator.deduplicate(results).map { it.symbol })
    }
}
