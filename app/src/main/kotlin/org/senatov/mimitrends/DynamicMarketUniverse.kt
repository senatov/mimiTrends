package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScannerCriteria

internal class DynamicMarketUniverse(
    private val discover: () -> List<String> = { emptyList() }
) {
    fun select(criteria: ScannerCriteria): DynamicUniverseSelection {
        val configured = MarketUniverseSelector.select(criteria).filterNot(::isBlockedVenue)
        val discovered = discover().map(String::uppercase)
            .filterNot(::isBlockedVenue)
            .filterNot { it in configured }
            .filter { MarketUniverseSelector.includes(it, criteria.marketRegion) }
            .distinct()
        return DynamicUniverseSelection((configured + discovered).distinct(), discovered)
    }

    private fun isBlockedVenue(symbol: String): Boolean = symbol.endsWith(".MI", ignoreCase = true)
}

internal data class DynamicUniverseSelection(val symbols: List<String>, val discovered: List<String>)
