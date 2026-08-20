package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

/** Keeps recently published events visible without presenting them as active signals. */
internal class RecentEventRetainer(
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS
) {
    private data class Event(val result: ScanResult, val lastActiveAtMillis: Long)

    private val events = linkedMapOf<String, Event>()

    @Synchronized
    fun merge(active: Collection<ScanResult>, nowMillis: Long, resultLimit: Int): List<ScanResult> {
        active.forEach { result -> retainActive(result, nowMillis) }
        removeExpired(nowMillis)
        return ranked(active.map(ScanResult::symbol).toSet(), nowMillis, resultLimit)
    }

    @Synchronized
    fun priorityUpdate(symbol: String, active: ScanResult?, nowMillis: Long): ScanResult? {
        if (active != null) {
            retainActive(active, nowMillis)
            return events.getValue(symbol).result
        }
        val previous = events[symbol] ?: return null
        return previous.result.takeIf { nowMillis - previous.lastActiveAtMillis < stabilityGraceMillis }
    }

    @Synchronized
    fun clear() = events.clear()

    private fun retainActive(result: ScanResult, nowMillis: Long) {
        val previous = events[result.symbol]?.result
        val source = transitionSource(previous, result)
        events[result.symbol] = Event(result.copy(signalSource = source), nowMillis)
    }

    private fun ranked(activeSymbols: Set<String>, nowMillis: Long, requestedLimit: Int): List<ScanResult> {
        val limit = requestedLimit.coerceAtLeast(1)
        val active = events.values.asSequence()
            .filter { it.result.symbol in activeSymbols }
            .map(Event::result)
            .sortedByDescending(ScanResult::anomalyScore)
            .toList()
        val cooling = events.values.asSequence()
            .filter { it.result.symbol !in activeSymbols && nowMillis - it.lastActiveAtMillis < stabilityGraceMillis }
            .map(Event::result)
            .sortedByDescending(ScanResult::anomalyScore)
        return (active.asSequence() + cooling).take(limit).toList()
    }

    private fun removeExpired(nowMillis: Long) {
        events.entries.removeIf { (_, event) -> nowMillis - event.lastActiveAtMillis >= retentionMillis }
    }

    private fun transitionSource(previous: ScanResult?, current: ScanResult): String {
        if (previous == null || !previous.signalSource.startsWith(V_REVERSAL) ||
            !current.signalSource.startsWith(V_REVERSAL)) return current.signalSource
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
        const val DEFAULT_RETENTION_MILLIS = 20 * 60_000L
        const val DEFAULT_STABILITY_GRACE_MILLIS = 2 * 60_000L
    }

    private val stabilityGraceMillis = minOf(retentionMillis, DEFAULT_STABILITY_GRACE_MILLIS)
}
