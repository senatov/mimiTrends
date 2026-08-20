package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScannerCriteria
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSymbolUniverseTest {
    @Test
    fun containsUniqueSymbols() {
        val symbols = ScannerCriteria().symbols

        assertEquals(symbols.size, symbols.distinct().size)
    }

    @Test
    fun retainsAmericanAndEuropeanListings() {
        val symbols = ScannerCriteria().symbols

        assertTrue("AAPL" in symbols)
        assertTrue("INTC" in symbols)
        assertTrue("SAP.DE" in symbols)
        assertTrue(setOf("RHM.DE", "CBK.DE").all(symbols::contains))
        assertFalse(setOf("TTE.PA", "SGO.PA", "LR.PA").any(symbols::contains))
        assertFalse(symbols.any { it.endsWith(".MI") })
        assertFalse(symbols.any { it.endsWith(".HE") })
        assertFalse(symbols.any { it.contains('.') && !it.endsWith(".DE") })
    }

    @Test
    fun usesCurrentTickerSymbols() {
        val symbols = ScannerCriteria().symbols

        assertTrue(setOf("MRSH", "FISV", "XYZ").all(symbols::contains))
        assertFalse(setOf("MMC", "FI", "SQ").any(symbols::contains))
    }
}
