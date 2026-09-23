package org.senatov.mimitrends.company

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

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.WallstreetOnlineMarketDataClient
import org.senatov.mimitrends.marketdata.WallstreetOnlineMover
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.slf4j.LoggerFactory

/** Turns the current public mover tables into symbols that the regular scanner can evaluate. */
internal class WallstreetOnlineDiscoveryService(
    private val movers: () -> List<WallstreetOnlineMover>,
    private val resolve: (String) -> String?,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    constructor(wallstreetOnline: WallstreetOnlineMarketDataClient, yahoo: YahooFinanceClient) : this(
        wallstreetOnline::loadMovers,
        { query -> yahoo.resolveEquity(query)?.symbol }
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val resolvedPaths = linkedMapOf<String, String>()
    private var cachedSymbols = emptyList<String>()
    private var refreshAfterMillis = 0L

    @Synchronized
    fun discover(): List<String> {
        val now = nowMillis()
        if (now < refreshAfterMillis) return cachedSymbols
        val current = movers().take(MAX_DISCOVERY_CANDIDATES)
        refreshAfterMillis = now + REFRESH_INTERVAL_MILLIS
        val currentPaths = current.mapTo(hashSetOf(), WallstreetOnlineMover::path)
        synchronized(resolvedPaths) { resolvedPaths.keys.retainAll(currentPaths) }
        val symbols = current.mapNotNull { mover -> resolve(mover) }.distinct()
        log.info(LogTag.API, "wallstreetONLINE discovery candidates={} resolved={}", current.size, symbols.size)
        if (symbols.isNotEmpty()) {
            cachedSymbols = symbols
        }
        return if (symbols.isNotEmpty()) symbols else cachedSymbols
    }

    private fun resolve(mover: WallstreetOnlineMover): String? {
        synchronized(resolvedPaths) { resolvedPaths[mover.path] }?.let { return it }
        return runCatching { resolve(mover.name) }
            .onFailure { error ->
                log.warn(
                    LogTag.API, "wallstreetONLINE discovery resolution failed path={} cause={}",
                    mover.path, error.toString()
                )
            }
            .getOrNull()
            ?.uppercase()
            ?.also { symbol -> synchronized(resolvedPaths) { resolvedPaths[mover.path] = symbol } }
    }

    private companion object {
        const val MAX_DISCOVERY_CANDIDATES = 20
        const val REFRESH_INTERVAL_MILLIS = 30 * 60_000L
    }
}
