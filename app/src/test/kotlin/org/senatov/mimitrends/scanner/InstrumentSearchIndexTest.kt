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
