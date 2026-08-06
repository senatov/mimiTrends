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
        val marketFloor = adaptiveFloor(fallbackLevels.flatten())
        fallbackLevels.forEach { level ->
            if (selected.size < target) level.asSequence()
                .filter { it.anomalyScore >= marketFloor }
                .sortedByDescending(ScanResult::anomalyScore)
                .forEach { candidate ->
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

    private fun adaptiveFloor(candidates: List<ScanResult>): Double {
        if (candidates.isEmpty()) return MIN_ADAPTIVE_SCORE
        val scores = candidates.map(ScanResult::anomalyScore).sorted()
        val index = ((scores.lastIndex * MARKET_PERCENTILE_FLOOR).toInt()).coerceIn(scores.indices)
        return maxOf(MIN_ADAPTIVE_SCORE, scores[index])
    }

    private const val MIN_RESULTS = 5
    private const val MAX_RESULTS = 15
    private const val MIN_ADAPTIVE_SCORE = 2.5
    private const val MARKET_PERCENTILE_FLOOR = 0.35
}

internal data class AdaptiveSelection(val results: List<ScanResult>, val adaptiveCount: Int)
