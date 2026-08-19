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
}
