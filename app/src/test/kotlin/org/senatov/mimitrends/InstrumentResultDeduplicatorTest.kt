package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentResultDeduplicatorTest {
    @Test fun `keeps the strongest listing when isin is shared`() {
        val deduplicator = InstrumentResultDeduplicator(
            loadIsin = { if (it.startsWith("STLA")) "NL00150001Q9" else null },
            loadCompanyName = { null }
        )
        val milan = TestScanResult.create(symbol = "STLAM.MI").copy(anomalyScore = 3.0)
        val paris = TestScanResult.create(symbol = "STLAP.PA").copy(anomalyScore = 4.0)

        assertEquals(listOf("STLAP.PA"), deduplicator.deduplicate(listOf(milan, paris)).map { it.symbol })
    }

    @Test fun `uses normalized company name when isin is unavailable`() {
        val names = mapOf("STLAM.MI" to "STELLANTIS NV", "STLAP.PA" to "STELLANTIS")
        val deduplicator = InstrumentResultDeduplicator({ null }, names::get)
        val newer = TestScanResult.create(symbol = "STLAM.MI").copy(updatedAtMillis = 2_000)
        val older = TestScanResult.create(symbol = "STLAP.PA").copy(updatedAtMillis = 1_000)

        assertEquals(listOf("STLAM.MI"), deduplicator.deduplicate(listOf(older, newer)).map { it.symbol })
    }

    @Test fun `does not merge equal names when known isins conflict`() {
        val isins = mapOf("AAA.DE" to "DE0000000001", "AAA.PA" to "FR0000000002")
        val deduplicator = InstrumentResultDeduplicator(isins::get) { "Example SE" }
        val results = listOf(TestScanResult.create(symbol = "AAA.DE"), TestScanResult.create(symbol = "AAA.PA"))

        assertEquals(2, deduplicator.deduplicate(results).size)
    }

    @Test fun `removes transaction-taxed results loaded from history`() {
        val deduplicator = InstrumentResultDeduplicator({ null }, { null })
        val results = listOf(TestScanResult.create(symbol = "TTE.PA"), TestScanResult.create(symbol = "AIR.PA"))

        assertEquals(listOf("AIR.PA"), deduplicator.deduplicate(results).map { it.symbol })
    }
}
