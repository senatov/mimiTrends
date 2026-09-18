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

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.CompanyProfile

class CompanyProfileMergerTest {
    @Test
    fun `remote refresh preserves cached logo and verified identity`() {
        val logo = byteArrayOf(1, 2, 3)
        val stored = CompanyProfile("SAP.DE", "SAP SE", "XETRA", "cached-logo", logo, 1L)
        val loaded = CompanyProfile("SAP.DE", "SAP", "Finnhub", "remote-logo", null, 2L)

        val merged = CompanyProfileMerger.merge(stored, loaded)

        assertEquals("SAP SE", merged.name)
        assertEquals("XETRA", merged.exchange)
        assertEquals("cached-logo", merged.logoUrl)
        assertArrayEquals(logo, merged.logoBytes)
    }
}
