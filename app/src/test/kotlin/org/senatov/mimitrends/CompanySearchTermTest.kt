package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CompanySearchTermTest {
    @Test fun `normalizes whitespace without shortening the displayed company name`() {
        assertEquals("BEIERSDORF AG I", CompanySearchTerm.normalizeDisplay("  BEIERSDORF AG                 I  "))
    }

    @Test fun `removes legal form and exchange class from a company name`() {
        assertEquals("Beiersdorf", CompanySearchTerm.from("BEIERSDORF AG                 I", "BEI.DE"))
    }

    @Test fun `removes French share label and preserves brand acronym`() {
        assertEquals("BNP Paribas", CompanySearchTerm.from("BNP PARIBAS ACT.A", "BNP.PA"))
    }

    @Test fun `preserves a multi-word searchable brand name`() {
        assertEquals("The Trade Desk", CompanySearchTerm.from("The Trade Desk, Inc.", "TTD"))
    }

    @Test fun `preserves meaningful group wording`() {
        assertEquals("Mercedes-Benz Group", CompanySearchTerm.from("Mercedes-Benz Group AG", "MBG.DE"))
    }

    @Test fun `removes a dangling connector after a legal form`() {
        assertEquals("Henkel", CompanySearchTerm.from("Henkel AG &", "HEN3.DE"))
    }

    @Test fun `removes compound German legal form`() {
        assertEquals("Henkel", CompanySearchTerm.from("Henkel AG & Co. KGaA", "HEN3.DE"))
    }

    @Test fun `falls back to ticker when company name is empty`() {
        assertEquals("BMW", CompanySearchTerm.from(" ", "BMW.DE"))
    }
}
