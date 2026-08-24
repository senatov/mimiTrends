package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CompanySearchTermTest {
    @Test fun `normalizes and shortens the displayed company name`() {
        assertEquals("Beiersdorf", CompanySearchTerm.normalizeDisplay("  BEIERSDORF AG                 I  "))
    }

    @Test fun `removes legal form and exchange class from a company name`() {
        assertEquals("Beiersdorf", CompanySearchTerm.from("BEIERSDORF AG                 I", "BEI.DE"))
    }

    @Test fun `removes French share label and preserves brand acronym`() {
        assertEquals("BNP Paribas", CompanySearchTerm.from("BNP PARIBAS ACT.A", "BNP.PA"))
    }

    @Test fun `removes a leading article from a multi-word brand name`() {
        assertEquals("Trade Desk", CompanySearchTerm.from("The Trade Desk, Inc.", "TTD"))
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

    @Test fun `removes legal suffixes punctuation and parenthesized article`() {
        val name = "Example Company Corporation, Inc. Co. Plc GmbH (The)"

        assertEquals("Example", CompanySearchTerm.normalizeDisplay(name))
        assertEquals("Example", CompanySearchTerm.from(name, "EXM"))
    }

    @Test fun `does not remove legal text embedded in a real word`() {
        assertEquals("Incyte Compass", CompanySearchTerm.normalizeDisplay("Incyte Compass, Inc."))
    }

    @Test
    fun `removes provider listing marker from mixed case company name`() {
        assertEquals("Symrise", CompanySearchTerm.normalizeDisplay("Symrise AG                 I"))
    }
}