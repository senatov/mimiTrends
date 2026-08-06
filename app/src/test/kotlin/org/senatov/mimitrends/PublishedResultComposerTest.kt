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

        val displayed = PublishedResultComposer.compose(current, saved, 3)

        assertEquals(listOf("NOKIA.HE", "SAP.DE", "ENR.DE"), displayed.map { it.symbol })
        assertEquals(current.single().price, displayed.first().price)
    }

    @Test
    fun `does not exceed the display limit`() {
        val saved = (1..10).map { TestScanResult.create(symbol = "TEST$it.DE") }

        assertEquals(5, PublishedResultComposer.compose(emptyList(), saved, 5).size)
    }
}
