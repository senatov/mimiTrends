package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScannerCriteria
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultSymbolUniverseTest {
    @Test
    fun containsExactly215UniqueSymbols() {
        val symbols = ScannerCriteria().symbols

        assertEquals(215, symbols.size)
        assertEquals(symbols.size, symbols.distinct().size)
    }

    @Test
    fun retainsAmericanAndEuropeanListings() {
        val symbols = ScannerCriteria().symbols

        assertTrue("AAPL" in symbols)
        assertTrue("INTC" in symbols)
        assertTrue("SAP.DE" in symbols)
        assertTrue(setOf("RHM.DE", "SHELL.AS", "AIR.PA", "STMPA.PA", "STLAP.PA").all(symbols::contains))
        assertFalse(setOf("TTE.PA", "SGO.PA", "LR.PA").any(symbols::contains))
        assertFalse(symbols.any { it.endsWith(".MI") })
        assertFalse(symbols.any { it.endsWith(".HE") })
    }

    @Test
    fun usesCurrentTickerSymbols() {
        val symbols = ScannerCriteria().symbols

        assertTrue(setOf("MRSH", "FISV", "XYZ").all(symbols::contains))
        assertFalse(setOf("MMC", "FI", "SQ").any(symbols::contains))
    }
}
