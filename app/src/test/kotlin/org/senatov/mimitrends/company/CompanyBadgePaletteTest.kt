package org.senatov.mimitrends.company

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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CompanyBadgePaletteTest {
    @Test
    fun `badge color is stable and ticker case independent`() {
        assertEquals(CompanyBadgePalette.forSymbol("SAP.DE"), CompanyBadgePalette.forSymbol("sap.de"))
    }
}
