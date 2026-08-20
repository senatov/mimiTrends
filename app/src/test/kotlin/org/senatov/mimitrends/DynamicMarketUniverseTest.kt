package org.senatov.mimitrends

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScannerCriteria

class DynamicMarketUniverseTest {
    @Test
    fun `uses only the configured broker universe`() {
        val selection = DynamicMarketUniverse().select(
            ScannerCriteria(symbols = listOf("AAPL", "MSFT", "AAPL"))
        )

        assertEquals(listOf("AAPL", "MSFT"), selection.symbols)
        assertEquals(emptyList<String>(), selection.discovered)
    }

    @Test
    fun `excludes taxed French issuers but retains non-French Paris listings`() {
        val selection = DynamicMarketUniverse().select(
            ScannerCriteria(symbols = listOf("TTE.PA", "MC.PA", "AIR.PA", "STMPA.PA", "STLAP.PA"))
        )

        assertEquals(listOf("AIR.PA", "STMPA.PA", "STLAP.PA"), selection.symbols)
    }

    @Test
    fun `adds current external movers to every selected universe`() {
        val selection = DynamicMarketUniverse { listOf("MU", "SRT3.DE", "MU") }.select(
            ScannerCriteria(symbols = listOf("AAPL"))
        )

        assertEquals(listOf("AAPL", "MU", "SRT3.DE"), selection.symbols)
        assertEquals(listOf("MU", "SRT3.DE"), selection.discovered)
    }
}
