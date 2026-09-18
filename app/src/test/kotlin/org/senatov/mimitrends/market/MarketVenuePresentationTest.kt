package org.senatov.mimitrends.market

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
