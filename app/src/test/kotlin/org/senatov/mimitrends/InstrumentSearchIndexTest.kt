package org.senatov.mimitrends

import org.senatov.mimitrends.db.InstrumentCatalogEntry
import kotlin.test.Test
import kotlin.test.assertEquals

class InstrumentSearchIndexTest {
    @Test
    fun `suggests an instrument from a three letter company prefix`() {
        val index = InstrumentSearchIndex(
            listOf(
                InstrumentCatalogEntry("IFX.DE", "Infineon Technologies", "XETRA"),
                InstrumentCatalogEntry("INTC", "Intel", "NASDAQ")
            )
        )

        assertEquals(listOf("IFX.DE"), index.search("Inf").map { it.symbol })
        assertEquals(listOf("INTC"), index.search("INT").map { it.symbol })
    }
}