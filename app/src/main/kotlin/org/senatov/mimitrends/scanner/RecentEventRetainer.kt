package org.senatov.mimitrends.scanner

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

import org.senatov.mimitrends.model.ScanResult

/** Keeps published signals until newer signals displace them from the table. */
internal class RecentEventRetainer {
    private val events = linkedMapOf<String, ScanResult>()

    @Synchronized
    fun merge(active: Collection<ScanResult>, resultLimit: Int): List<ScanResult> {
        val activeSymbols = active.mapTo(HashSet(), ScanResult::symbol)
        events.replaceAll { symbol, result -> result.copy(isRetained = symbol !in activeSymbols) }
        active.forEach(::retainActive)
        return ranked(resultLimit)
    }

    @Synchronized
    fun priorityUpdate(symbol: String, active: ScanResult?): ScanResult? {
        if (active != null) {
            retainActive(active)
            return events[symbol]
        }
        val previous = events[symbol] ?: return null
        return previous.copy(isRetained = true).also { events[symbol] = it }
    }

    @Synchronized
    fun clear() = events.clear()

    private fun retainActive(result: ScanResult) {
        val previous = events[result.symbol]
        val source = transitionSource(previous, result)
        events[result.symbol] = result.copy(signalSource = source, isRetained = false)
    }

    private fun ranked(requestedLimit: Int): List<ScanResult> {
        val displayed = events.values.sortedWith(ScannerResultOrder.newestFirst)
            .take(requestedLimit.coerceAtLeast(1))
        val visibleSymbols = displayed.mapTo(HashSet(), ScanResult::symbol)
        events.keys.retainAll(visibleSymbols)
        return displayed
    }

    private fun transitionSource(previous: ScanResult?, current: ScanResult): String {
        if (previous == null || !previous.signalSource.startsWith(V_REVERSAL) ||
            !current.signalSource.startsWith(V_REVERSAL)
        ) return current.signalSource
        val oldDirection = direction(previous.signalSource)
        val newDirection = direction(current.signalSource)
        return if (oldDirection != null && newDirection != null && oldDirection != newDirection) {
            current.signalSource.substringBefore(" after ") + " after $oldDirection"
        } else current.signalSource
    }

    private fun direction(source: String): Char? = when {
        '↑' in source -> '↑'
        '↓' in source -> '↓'
        else -> null
    }

    private companion object {
        const val V_REVERSAL = "V-Reversal"
    }
}
