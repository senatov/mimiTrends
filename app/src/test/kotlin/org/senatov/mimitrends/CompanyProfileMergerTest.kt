package org.senatov.mimitrends

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
