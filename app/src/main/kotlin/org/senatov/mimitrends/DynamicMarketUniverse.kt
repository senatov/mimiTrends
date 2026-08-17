package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.senatov.mimitrends.model.MarketRegion
import org.senatov.mimitrends.model.ScannerCriteria
import org.slf4j.Logger
import java.time.Instant

internal class DynamicMarketUniverse(
    private val yahoo: YahooFinanceClient,
    private val log: Logger,
    private val now: () -> Long = { Instant.now().epochSecond }
) {
    private var cachedAt = 0L
    private var cachedSymbols = emptyList<String>()

    fun select(criteria: ScannerCriteria): DynamicUniverseSelection {
        val configured = MarketUniverseSelector.select(criteria)
        if (criteria.marketRegion == MarketRegion.EUROPE) return DynamicUniverseSelection(configured, emptyList())
        val current = now()
        if (current - cachedAt >= CACHE_SECONDS || cachedAt == 0L) {
            runCatching { yahoo.loadDayGainers(DISCOVERY_LIMIT) }
                .onSuccess { leaders ->
                    cachedSymbols = leaders.asSequence()
                        .filter { it.price >= criteria.minPrice && it.changePercent >= MIN_GAIN_PERCENT }
                        .map { it.symbol }
                        .filter(::isSupportedSymbol)
                        .take(DISCOVERY_LIMIT)
                        .toList()
                    cachedAt = current
                    log.info(LogTag.API, "dynamic universe refreshed discovered={}", cachedSymbols.size)
                }
                .onFailure { error ->
                    log.warn(LogTag.API, "dynamic universe refresh failed; configured universe retained", error)
                    if (cachedAt == 0L) cachedAt = current
                }
        }
        val additions = cachedSymbols.filterNot(configured.toHashSet()::contains)
        return DynamicUniverseSelection((configured + additions).distinct(), additions)
    }

    private fun isSupportedSymbol(symbol: String): Boolean =
        symbol.matches(Regex("[A-Z][A-Z0-9-]{0,9}")) && '^' !in symbol && '=' !in symbol

    private companion object {
        const val CACHE_SECONDS = 15 * 60L
        const val DISCOVERY_LIMIT = 25
        const val MIN_GAIN_PERCENT = 1.0
    }
}

internal data class DynamicUniverseSelection(val symbols: List<String>, val discovered: List<String>)
