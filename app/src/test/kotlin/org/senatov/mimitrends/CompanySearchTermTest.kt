package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CompanySearchTermTest {
    @Test fun `normalizes whitespace without shortening the displayed company name`() {
        assertEquals("BEIERSDORF AG I", CompanySearchTerm.normalizeDisplay("  BEIERSDORF AG                 I  "))
    }

    @Test fun `removes legal form and exchange class from a company name`() {
        assertEquals("BEIERSDORF", CompanySearchTerm.from("BEIERSDORF AG                 I", "BEI.DE"))
    }

    @Test fun `preserves a multi-word searchable brand name`() {
        assertEquals("The Trade Desk", CompanySearchTerm.from("The Trade Desk, Inc.", "TTD"))
    }

    @Test fun `preserves meaningful group wording`() {
        assertEquals("Mercedes-Benz Group", CompanySearchTerm.from("Mercedes-Benz Group AG", "MBG.DE"))
    }

    @Test fun `falls back to ticker when company name is empty`() {
        assertEquals("BMW", CompanySearchTerm.from(" ", "BMW.DE"))
    }
}
