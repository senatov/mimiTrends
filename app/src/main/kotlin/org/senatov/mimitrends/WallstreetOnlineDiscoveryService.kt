package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.WallstreetOnlineMarketDataClient
import org.senatov.mimitrends.marketdata.WallstreetOnlineMover
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.slf4j.LoggerFactory

/** Turns the current public mover tables into symbols that the regular scanner can evaluate. */
internal class WallstreetOnlineDiscoveryService(
    private val movers: () -> List<WallstreetOnlineMover>,
    private val resolve: (String) -> String?,
) {
    constructor(wallstreetOnline: WallstreetOnlineMarketDataClient, yahoo: YahooFinanceClient) : this(
        wallstreetOnline::loadMovers,
        { query -> yahoo.resolveEquity(query)?.symbol }
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val resolvedPaths = linkedMapOf<String, String>()

    fun discover(): List<String> {
        val current = movers()
        val currentPaths = current.mapTo(hashSetOf(), WallstreetOnlineMover::path)
        synchronized(resolvedPaths) { resolvedPaths.keys.retainAll(currentPaths) }
        val symbols = current.mapNotNull { mover -> resolve(mover) }.distinct()
        log.info(LogTag.API, "wallstreetONLINE discovery candidates={} resolved={}", current.size, symbols.size)
        return symbols
    }

    private fun resolve(mover: WallstreetOnlineMover): String? {
        synchronized(resolvedPaths) { resolvedPaths[mover.path] }?.let { return it }
        return runCatching { resolve(mover.name) }
            .onFailure { error ->
                log.warn(LogTag.API, "wallstreetONLINE discovery resolution failed path={} cause={}",
                    mover.path, error.toString())
            }
            .getOrNull()
            ?.uppercase()
            ?.also { symbol -> synchronized(resolvedPaths) { resolvedPaths[mover.path] = symbol } }
    }
}
