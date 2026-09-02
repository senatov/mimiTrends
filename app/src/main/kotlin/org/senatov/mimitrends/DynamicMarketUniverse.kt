package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import kotlin.math.abs
import kotlin.math.ln1p

internal class DynamicMarketUniverse(
    private val discover: () -> List<String> = { emptyList() },
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val activity = mutableMapOf<String, Double>()
    private var snapshot: UniverseSnapshot? = null
    private var pinned = emptySet<String>()

    @Synchronized
    fun replacePinned(symbols: Collection<String>) {
        pinned = symbols.mapTo(linkedSetOf()) { it.trim().uppercase() }
        snapshot = null
    }

    @Synchronized
    fun select(criteria: ScannerCriteria): DynamicUniverseSelection {
        val configured = MarketUniverseSelector.select(criteria)
        val signature = UniverseSignature(configured, criteria.marketRegion.name)
        val previous = snapshot?.takeIf { it.signature == signature }
        previous?.takeIf { nowMillis() - it.createdAt < REFRESH_INTERVAL_MILLIS }
            ?.let { return it.selection }
        val discovered = discover().map(String::uppercase)
            .filterNot(org.senatov.mimitrends.model.FinancialTransactionTaxExclusions::contains)
            .filter { MarketUniverseSelector.includes(it, criteria.marketRegion) }
            .distinct()
        val regionalSymbols = listOf(false, true).map { european ->
            val configuredRegion = configured.filter { it.contains('.') == european }
            val discoveredRegion = discovered.filter { it.contains('.') == european }
            val core = configuredRegion.take(STABLE_CORE_SIZE)
            val discoveryRanks = discoveredRegion.withIndex().associate { it.value to it.index }
            val desired = (core + (configuredRegion + discoveredRegion).distinct()
                .filterNot(core::contains)
                .sortedByDescending { score(it, discoveryRanks[it]) })
                .distinct().take(MAX_SYMBOLS_PER_REGION)
            stabilize(previous?.selection?.symbols.orEmpty().filter { it.contains('.') == european }, desired, core)
        }
        val symbols = (regionalSymbols.flatten() + pinned).distinct()
        val ranks = regionalSymbols.flatMap { region ->
            region.mapIndexed { index, symbol -> symbol to index + 1 }
        }.toMap()
        return DynamicUniverseSelection(symbols, symbols.filter(discovered::contains), ranks).also {
            snapshot = UniverseSnapshot(signature, nowMillis(), it)
        }
    }

    @Synchronized
    fun record(results: Collection<ScanResult>) {
        results.forEach { result ->
            val freshnessMinutes = FeedFreshness.ageMinutes(result.analysisUpdatedAtMillis, nowMillis()).coerceAtLeast(0)
            val freshness = (FRESHNESS_WINDOW_MINUTES - freshnessMinutes).coerceAtLeast(0).toDouble() /
                    FRESHNESS_WINDOW_MINUTES
            val observed = ln1p(result.sessionTurnover.coerceAtLeast(0.0)) / TURNOVER_SCALE +
                    abs(result.windowChangePercent) + freshness
            activity.merge(result.symbol, observed) { previous, current ->
                previous * ACTIVITY_MEMORY + current * (1.0 - ACTIVITY_MEMORY)
            }
        }
    }

    private fun score(symbol: String, discoveryRank: Int?): Double =
        (activity[symbol] ?: 0.0) + (discoveryRank?.let { DISCOVERY_WEIGHT / (it + 1.0) } ?: 0.0)

    private fun stabilize(previous: List<String>, desired: List<String>, core: List<String>): List<String> {
        if (previous.isEmpty()) return desired
        val retained = previous.take(MAX_SYMBOLS_PER_REGION).toMutableList()
        core.filterNot(retained::contains).forEach { retained.add(0, it) }
        desired.filterNot(retained::contains).take(MAX_REPLACEMENTS_PER_REFRESH).forEach { newcomer ->
            if (retained.size >= desired.size) {
                val removable = retained.indexOfLast { it !in core && it !in desired }
                    .takeIf { it >= 0 } ?: retained.indexOfLast { it !in core }
                if (removable >= 0) retained.removeAt(removable)
            }
            retained += newcomer
        }
        return (desired.filter(retained::contains) + retained).distinct().take(MAX_SYMBOLS_PER_REGION)
    }

    private data class UniverseSignature(val configured: List<String>, val region: String)
    private data class UniverseSnapshot(
        val signature: UniverseSignature,
        val createdAt: Long,
        val selection: DynamicUniverseSelection
    )

    private companion object {
        const val MAX_SYMBOLS_PER_REGION = 200
        const val STABLE_CORE_SIZE = 35
        const val MAX_REPLACEMENTS_PER_REFRESH = 5
        const val FRESHNESS_WINDOW_MINUTES = 30L
        const val TURNOVER_SCALE = 5.0
        const val ACTIVITY_MEMORY = 0.65
        const val DISCOVERY_WEIGHT = 3.0
        const val REFRESH_INTERVAL_MILLIS = 4 * 60 * 60 * 1_000L
    }
}

internal data class DynamicUniverseSelection(
    val symbols: List<String>,
    val discovered: List<String>,
    val ranks: Map<String, Int>
)
