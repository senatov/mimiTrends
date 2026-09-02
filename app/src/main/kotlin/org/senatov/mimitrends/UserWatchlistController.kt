package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal class UserWatchlistController(
    private val repository: MarketRepository,
    private val universe: DynamicMarketUniverse,
    private val onChanged: () -> Unit
) {
    val symbols: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(repository.loadUserWatchlist())
    }
    private val liveSources = ConcurrentHashMap<String, String>()

    val actions = InstrumentWatchlistActions(
        search = { query ->
            CompletableFuture.supplyAsync {
                repository.searchInstruments(query).map { TableSearchSuggestion(it.symbol, it.name, it.exchange) }
            }
        },
        add = ::add,
        remove = ::remove,
        contains = symbols::contains,
        liveSource = { symbol -> liveSources[symbol] ?: "CACHE" }
    )

    init {
        universe.replacePinned(symbols)
    }

    fun observe(observation: MarketPriceObservation) {
        liveSources[observation.symbol] = observation.provider
    }

    private fun add(symbol: String) {
        val normalized = symbol.trim().uppercase()
        if (!symbols.add(normalized)) return
        repository.addToUserWatchlist(normalized)
        changed()
    }

    private fun remove(symbol: String) {
        val normalized = symbol.trim().uppercase()
        if (!symbols.remove(normalized)) return
        repository.removeFromUserWatchlist(normalized)
        changed()
    }

    private fun changed() {
        universe.replacePinned(symbols)
        onChanged()
    }
}