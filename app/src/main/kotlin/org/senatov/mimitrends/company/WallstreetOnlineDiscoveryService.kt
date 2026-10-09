package org.senatov.mimitrends.company

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

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.WallstreetOnlineMarketDataClient
import org.senatov.mimitrends.marketdata.WallstreetOnlineCategory
import org.senatov.mimitrends.marketdata.WallstreetOnlineRankedMover
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.senatov.mimitrends.providers.SourceActivity
import org.slf4j.LoggerFactory

/** Turns the current public mover tables into symbols that the regular scanner can evaluate. */
internal class WallstreetOnlineDiscoveryService(
    private val rankings: () -> List<WallstreetOnlineRankedMover>,
    private val resolve: (String) -> String?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val activity: SourceActivity? = null
) {
    constructor(
        wallstreetOnline: WallstreetOnlineMarketDataClient, yahoo: YahooFinanceClient,
        activity: SourceActivity? = null
    ) : this(
        wallstreetOnline::loadRankings,
        { query -> yahoo.resolveEquity(query)?.symbol }, activity = activity
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val resolvedPaths = linkedMapOf<String, String>()
    private var cachedSymbols = emptyList<String>()
    private var cachedCategories = emptyMap<String, Set<WallstreetOnlineCategory>>()
    private var refreshAfterMillis = 0L

    @Synchronized
    fun categories(symbol: String): Set<WallstreetOnlineCategory> = cachedCategories[symbol].orEmpty()

    @Synchronized
    fun discover(): List<String> {
        val now = nowMillis()
        if (now < refreshAfterMillis) return cachedSymbols
        val loaded = try {
            rankings()
        } catch (error: Exception) {
            activity?.record("wallstreetONLINE", 0, 0, failed = true)
            refreshAfterMillis = now + FAILURE_RETRY_MILLIS
            log.warn(LogTag.API, "wallstreetONLINE discovery failed; retaining cached symbols", error)
            return cachedSymbols
        }
        val current = selectCandidates(loaded)
        refreshAfterMillis = now + REFRESH_INTERVAL_MILLIS
        val currentPaths = current.mapTo(hashSetOf()) { it.mover.path }
        synchronized(resolvedPaths) { resolvedPaths.keys.retainAll(currentPaths) }
        val categoriesByPath = loaded.groupBy { it.mover.path }
            .mapValues { (_, entries) -> entries.mapTo(linkedSetOf(), WallstreetOnlineRankedMover::category) }
        val resolved = current.mapNotNull { ranked ->
            resolve(ranked.mover.name, ranked.mover.path)?.let { it to categoriesByPath[ranked.mover.path].orEmpty() }
        }
        val symbols = resolved.map(Pair<String, Set<WallstreetOnlineCategory>>::first).distinct()
        activity?.record("wallstreetONLINE", loaded.size, symbols.size)
        log.info(LogTag.API, "wallstreetONLINE discovery candidates={} resolved={}", current.size, symbols.size)
        if (symbols.isNotEmpty()) {
            cachedSymbols = symbols
            cachedCategories = resolved.groupBy(Pair<String, Set<WallstreetOnlineCategory>>::first)
                .mapValues { (_, values) -> values.flatMapTo(linkedSetOf()) { it.second } }
        }
        return if (symbols.isNotEmpty()) symbols else cachedSymbols
    }

    private fun resolve(name: String, path: String): String? {
        synchronized(resolvedPaths) { resolvedPaths[path] }?.let { return it }
        return runCatching { resolve(name) }
            .onFailure { error ->
                log.warn(
                    LogTag.API, "wallstreetONLINE discovery resolution failed path={} cause={}",
                    path, error.toString()
                )
            }
            .getOrNull()
            ?.uppercase()
            ?.also { symbol -> synchronized(resolvedPaths) { resolvedPaths[path] = symbol } }
    }

    internal fun selectCandidates(values: List<WallstreetOnlineRankedMover>): List<WallstreetOnlineRankedMover> {
        val pools = CATEGORY_ORDER.map { category ->
            values.asSequence().filter { it.category == category }.sortedBy(WallstreetOnlineRankedMover::rank)
                .distinctBy { it.mover.path }.toList()
        }
        val selected = mutableListOf<WallstreetOnlineRankedMover>()
        val paths = hashSetOf<String>()
        var rank = 0
        while (selected.size < MAX_DISCOVERY_CANDIDATES && pools.any { rank < it.size }) {
            pools.forEach { pool ->
                pool.getOrNull(rank)?.takeIf { selected.size < MAX_DISCOVERY_CANDIDATES && paths.add(it.mover.path) }
                    ?.let(selected::add)
            }
            rank++
        }
        return selected
    }

    private companion object {
        const val MAX_DISCOVERY_CANDIDATES = 40
        const val REFRESH_INTERVAL_MILLIS = 30 * 60_000L
        const val FAILURE_RETRY_MILLIS = 5 * 60_000L
        val CATEGORY_ORDER = listOf(
            WallstreetOnlineCategory.FLOP, WallstreetOnlineCategory.MOST_TRADED,
            WallstreetOnlineCategory.TOP, WallstreetOnlineCategory.GAP_DOWN,
            WallstreetOnlineCategory.GAP_UP, WallstreetOnlineCategory.REVERSAL_DOWN,
            WallstreetOnlineCategory.REVERSAL_UP, WallstreetOnlineCategory.HIGH_RANGE
        )
    }
}
