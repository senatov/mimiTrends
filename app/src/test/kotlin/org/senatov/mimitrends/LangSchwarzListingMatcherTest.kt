package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.marketdata.LangSchwarzListing
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LangSchwarzListingMatcherTest {
    @Test fun `matches a German instrument by WKN embedded in its ISIN`() {
        val listing = listing("ENER6Y", "SIEMENS ENERGY AG NA O.N.")

        val matched = LangSchwarzListingMatcher.match(
            "ENR.DE", "Siemens Energy AG N", listOf("DE000ENER6Y0"), listOf(listing)
        )

        assertEquals(listing, matched)
    }

    @Test fun `matches a European instrument by its simplified company name`() {
        val listing = listing("887771", "BNP PARIBAS INH. EO 2")

        val matched = LangSchwarzListingMatcher.match(
            "BNP.PA", "BNP PARIBAS ACT.A", emptyList(), listOf(listing)
        )

        assertEquals(listing, matched)
    }

    @Test
    fun `rejects ambiguous company-name matches without an identifier`() {
        val first = listing("111111", "ALPHA ENERGY HOLDING")
        val second = listing("222222", "ALPHA ENERGY SYSTEMS")

        assertNull(
            LangSchwarzListingMatcher.match(
                "ALPHA.DE", "Alpha Energy", emptyList(), listOf(first, second)
            )
        )
    }

    private fun listing(wkn: String, name: String) = LangSchwarzListing(
        "42", wkn, name, "/de/aktie/42", 100.0, 100.2, null, 1_000
    )
}