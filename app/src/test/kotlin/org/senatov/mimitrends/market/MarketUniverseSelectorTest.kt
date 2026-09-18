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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.senatov.mimitrends.model.MarketRegion

class MarketUniverseSelectorTest {
    @Test
    fun `allows US and German listings only`() {
        assertTrue(MarketUniverseSelector.includes("AAPL", MarketRegion.BOTH))
        assertTrue(MarketUniverseSelector.includes("IFX.DE", MarketRegion.BOTH))
        assertTrue(MarketUniverseSelector.includes("IFX.DE", MarketRegion.EUROPE))
        assertTrue(MarketUniverseSelector.includes("AAPL", MarketRegion.US))
    }

    @Test
    fun `rejects non German European listings from every combined universe`() {
        listOf("ASML.AS", "MC.PA", "ENEL.MI", "NOKIA.HE", "CARL-B.CO", "SAP.FRA", "AIR.PAR")
            .forEach { symbol ->
                assertFalse(MarketUniverseSelector.includes(symbol, MarketRegion.BOTH), symbol)
                assertFalse(MarketUniverseSelector.includes(symbol, MarketRegion.EUROPE), symbol)
            }
    }
}
