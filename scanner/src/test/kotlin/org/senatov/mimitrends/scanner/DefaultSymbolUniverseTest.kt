package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScannerCriteria
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultSymbolUniverseTest {
    @Test
    fun containsExactly256UniqueSymbols() {
        val symbols = ScannerCriteria().symbols

        assertEquals(256, symbols.size)
        assertEquals(symbols.size, symbols.distinct().size)
    }

    @Test
    fun retainsAmericanAndEuropeanListings() {
        val symbols = ScannerCriteria().symbols

        assertTrue("AAPL" in symbols)
        assertTrue("INTC" in symbols)
        assertTrue("SAP.DE" in symbols)
        assertTrue("STLAM.MI" in symbols)
        assertTrue("NOKIA.HE" in symbols)
    }
}
