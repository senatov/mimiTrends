package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScannerCriteria

internal class DynamicMarketUniverse {
    fun select(criteria: ScannerCriteria): DynamicUniverseSelection {
        val configured = MarketUniverseSelector.select(criteria).filterNot(::isBlockedVenue)
        return DynamicUniverseSelection(configured.distinct(), emptyList())
    }

    private fun isBlockedVenue(symbol: String): Boolean = symbol.endsWith(".MI", ignoreCase = true)
}

internal data class DynamicUniverseSelection(val symbols: List<String>, val discovered: List<String>)
