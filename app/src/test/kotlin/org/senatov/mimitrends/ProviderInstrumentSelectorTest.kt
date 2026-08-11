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

    private fun instrument(provider: String, isin: String, name: String) = ProviderInstrument(
        provider, "CPR.MI", isin, "TEST", "EUR", name
    )
}
