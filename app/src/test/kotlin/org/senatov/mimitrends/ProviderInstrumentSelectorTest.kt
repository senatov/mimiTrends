package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ProviderInstrument
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ProviderInstrumentSelectorTest {
    @Test fun `rejects an unrelated cached company and selects the listing market instrument`() {
        val wrong = instrument("TRADEGATE", "CA75888V1004", "REGEN III Corp.")
        val campari = instrument("EURONEXT", "NL0015435975", "CAMPARI")

        val selected = ProviderInstrumentSelector.select(
            "CPR.MI", "CAMPARI", listOf(wrong, campari)
        ) { true }

        assertEquals("NL0015435975", selected?.identifier)
        assertFalse(ProviderInstrumentSelector.matchesCompany("CPR.MI", "CAMPARI", wrong.resolvedName))
    }

    @Test fun `accepts equivalent names with different legal suffixes`() {
        assertEquals(true, ProviderInstrumentSelector.matchesCompany(
            "NOKIA.HE", "NOKIA CORPORATION", "Nokia Corp."
        ))
    }

    @Test fun `rejects TXNM Energy for Texas Instruments ticker`() {
        assertFalse(ProviderInstrumentSelector.matchesCompany(
            "TXN", "Texas Instruments Incorporated", "TXNM Energy Inc."
        ))
    }

    @Test fun `prefers isin identity over an ambiguous ticker and company name`() {
        val wrong = instrument("EURONEXT", "US8261975010", "Siemens AG")
        val siemens = instrument("TRADEGATE", "DE0007236101", "Siemens AG")

        val selected = ProviderInstrumentSelector.select(
            "SIE.DE", "Siemens AG", listOf(wrong, siemens), "DE0007236101"
        ) { true }

        assertEquals("DE0007236101", selected?.identifier)
    }

    @Test fun `does not fall back to a conflicting ticker candidate when isin is known`() {
        val wrong = instrument("EURONEXT", "US8261975010", "Siemens AG")

        val selected = ProviderInstrumentSelector.select(
            "SIE.DE", "Siemens AG", listOf(wrong), "DE0007236101"
        ) { true }

        assertEquals(null, selected)
    }

    @Test fun `rejects a cached provider instrument with a conflicting isin`() {
        val wrong = instrument("EURONEXT", "US8261975010", "Siemens AG")

        assertFalse(ProviderInstrumentSelector.matchesIdentity("DE0007236101", wrong))
    }

    private fun instrument(provider: String, isin: String, name: String) = ProviderInstrument(
        provider, "CPR.MI", isin, "TEST", "EUR", name
    )
}
