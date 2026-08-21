package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScannerCriteria

internal class DynamicMarketUniverse(
    private val discover: () -> List<String> = { emptyList() }
) {
    fun select(criteria: ScannerCriteria): DynamicUniverseSelection {
        val configured = MarketUniverseSelector.select(criteria).filterNot(::isBlockedVenue)
        val discovered = discover().map(String::uppercase)
            .filterNot(::isBlockedVenue)
            .filter { MarketUniverseSelector.includes(it, criteria.marketRegion) }
            .distinct()
        val regionalSymbols = listOf(false, true).map { european ->
            val configuredRegion = configured.filter { it.contains('.') == european }
            val discoveredRegion = discovered.filter { it.contains('.') == european }
            (discoveredRegion + configuredRegion).distinct().take(MAX_SYMBOLS_PER_REGION)
        }
        val symbols = regionalSymbols.flatten()
        val ranks = regionalSymbols.flatMap { region ->
            region.mapIndexed { index, symbol -> symbol to index + 1 }
        }.toMap()
        return DynamicUniverseSelection(symbols, symbols.filter { it in discovered },
            ranks)
    }

    private fun isBlockedVenue(symbol: String): Boolean = symbol.endsWith(".MI", ignoreCase = true)

    private companion object { const val MAX_SYMBOLS_PER_REGION = 50 }
}

internal data class DynamicUniverseSelection(
    val symbols: List<String>,
    val discovered: List<String>,
    val ranks: Map<String, Int>
)
