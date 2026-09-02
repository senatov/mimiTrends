package org.senatov.mimitrends

import kotlin.test.Test
import kotlin.test.assertEquals

class MarketVenuePresentationTest {
    @Test
    fun `uses the live provider venue before the ticker home market`() {
        assertEquals(MarketCountry.DE, MarketVenuePresentation.forInstrument("IFX.DE", "TRADEGATE").country)
        assertEquals(MarketCountry.US, MarketVenuePresentation.forInstrument("NVDA", "FINNHUB").country)
        assertEquals(MarketCountry.FR, MarketVenuePresentation.forInstrument("AIR.PA", "YAHOO").country)
    }
}
