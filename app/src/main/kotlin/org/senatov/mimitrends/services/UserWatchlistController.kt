package org.senatov.mimitrends.services

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.senatov.mimitrends.db.MarketRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

internal class UserWatchlistController(
    private val repository: MarketRepository,
    private val universe: DynamicMarketUniverse,
    private val onChanged: () -> Unit,
    private val searchRemote: (String) -> List<TableSearchSuggestion>,
    private val lookupExecutor: Executor
) {
    val symbols: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(repository.loadUserWatchlist())
    }
    private val liveSources = ConcurrentHashMap<String, String>()
    private val searchIndex = InstrumentSearchIndex(repository.loadInstrumentCatalog())

    val actions = InstrumentWatchlistActions(
        search = ::search,
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

    private fun search(query: String): CompletableFuture<List<TableSearchSuggestion>> =
        CompletableFuture.supplyAsync({
            val local = searchIndex.search(query)
            if (local.isNotEmpty()) return@supplyAsync local
            val remote = searchRemote(query)
            remote.distinctBy(TableSearchSuggestion::symbol).take(12)
        }, lookupExecutor)

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
