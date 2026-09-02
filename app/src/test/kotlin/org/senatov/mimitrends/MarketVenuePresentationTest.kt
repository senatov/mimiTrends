package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals

class MarketVenuePresentationTest {
    @Test
    fun `uses the live provider venue before the ticker home market`() {
        assertEquals("🇩🇪", MarketVenuePresentation.forInstrument("IFX.DE", "TRADEGATE").flag)
        assertEquals("🇺🇸", MarketVenuePresentation.forInstrument("NVDA", "FINNHUB").flag)
        assertEquals("🇫🇷", MarketVenuePresentation.forInstrument("AIR.PA", "YAHOO").flag)
    }
}