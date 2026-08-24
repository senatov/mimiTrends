package org.senatov.mimitrends

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompanyBadgePaletteTest {
    @Test
    fun `badge color is stable and ticker case independent`() {
        assertEquals(CompanyBadgePalette.forSymbol("SAP.DE"), CompanyBadgePalette.forSymbol("sap.de"))
    }
}
