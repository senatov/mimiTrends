package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals

class PublishedResultComposerTest {
    @Test
    fun `fills a sparse current scan with distinct saved results`() {
        val current = listOf(TestScanResult.create(symbol = "NOKIA.HE"))
        val saved = listOf(
            TestScanResult.create(symbol = "NOKIA.HE").copy(price = 1.0),
            TestScanResult.create(symbol = "SAP.DE"),
            TestScanResult.create(symbol = "ENR.DE")
        )

        val displayed = PublishedResultComposer.compose(current, saved, 3, nowEpochSeconds = 1)

        assertEquals(listOf("NOKIA.HE", "SAP.DE", "ENR.DE"), displayed.map { it.symbol })
        assertEquals(current.single().price, displayed.first().price)
    }

    @Test
    fun `does not exceed the display limit`() {
        val saved = (1..10).map { TestScanResult.create(symbol = "TEST$it.DE") }

        assertEquals(5, PublishedResultComposer.compose(emptyList(), saved, 5, nowEpochSeconds = 1).size)
    }

    @Test
    fun `excludes stale saved results from an open-market table`() {
        val current = TestScanResult.create(symbol = "LIVE").copy(
            updatedAtMillis = 10_000_000, analysisUpdatedAtMillis = 10_000_000
        )
        val recent = TestScanResult.create(symbol = "RECENT").copy(
            updatedAtMillis = 9_880_000, analysisUpdatedAtMillis = 9_880_000
        )
        val stale = TestScanResult.create(symbol = "STALE").copy(
            updatedAtMillis = 10_000_000, analysisUpdatedAtMillis = 9_759_000
        )

        val displayed = PublishedResultComposer.compose(
            listOf(current), listOf(recent, stale), 5, nowEpochSeconds = 10_000
        )

        assertEquals(listOf("LIVE", "RECENT"), displayed.map { it.symbol })
    }
}
