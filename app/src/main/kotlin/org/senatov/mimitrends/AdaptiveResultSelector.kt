package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

internal object AdaptiveResultSelector {
    fun select(
        strict: Collection<ScanResult>,
        fallbackLevels: List<Collection<ScanResult>>,
        requestedTarget: Int,
        requestedLimit: Int
    ): AdaptiveSelection {
        val limit = requestedLimit.coerceIn(MIN_RESULTS, MAX_RESULTS)
        val target = requestedTarget.coerceIn(MIN_RESULTS, limit)
        val selected = strict.associateByTo(linkedMapOf(), ScanResult::symbol)
        fallbackLevels.forEach { level ->
            if (selected.size < target) level.sortedByDescending(ScanResult::anomalyScore).forEach { candidate ->
                if (selected.size < target) selected.putIfAbsent(candidate.symbol, candidate)
            }
        }
        val results = selected.values.sortedByDescending(ScanResult::anomalyScore).take(limit)
        val adaptiveCount = results.count {
            it.signalSource.contains("relaxed") || it.signalSource.startsWith("Trend") ||
                it.signalSource.startsWith("Steady rise") || it.signalSource.startsWith("Recovery")
        }
        return AdaptiveSelection(results, adaptiveCount)
    }

    private const val MIN_RESULTS = 5
    private const val MAX_RESULTS = 15
}

internal data class AdaptiveSelection(val results: List<ScanResult>, val adaptiveCount: Int)
