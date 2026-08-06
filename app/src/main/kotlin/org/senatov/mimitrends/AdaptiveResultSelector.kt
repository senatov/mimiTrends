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
        val selected = strict.filter { CandidateQualityGate.qualifies(it, adaptive = false) }
            .associateByTo(linkedMapOf(), ScanResult::symbol)
        val adaptiveCandidates = fallbackLevels.flatten()
            .filter { CandidateQualityGate.qualifies(it, adaptive = true) }
        val marketFloor = adaptiveFloor(adaptiveCandidates)
        var adaptiveAdded = 0
        fallbackLevels.forEach { level ->
            if (selected.size < target && adaptiveAdded < MAX_ADAPTIVE_RESULTS) level.asSequence()
                .filter { it in adaptiveCandidates }
                .filter { CandidateQualityGate.attentionScore(it) >= marketFloor }
                .sortedWith(attentionComparator())
                .forEach { candidate ->
                if (selected.size < target && adaptiveAdded < MAX_ADAPTIVE_RESULTS &&
                    selected.putIfAbsent(candidate.symbol, candidate) == null) adaptiveAdded++
            }
        }
        val results = selected.values.sortedWith(attentionComparator()).take(limit)
        val adaptiveCount = results.count {
            it.signalSource.contains("relaxed") || it.signalSource.startsWith("Trend") ||
                it.signalSource.startsWith("Steady rise") || it.signalSource.startsWith("Recovery")
        }
        return AdaptiveSelection(results, adaptiveCount)
    }

    private fun adaptiveFloor(candidates: List<ScanResult>): Double {
        if (candidates.isEmpty()) return MIN_ATTENTION_SCORE
        val scores = candidates.map(CandidateQualityGate::attentionScore).sorted()
        val index = ((scores.lastIndex * MARKET_PERCENTILE_FLOOR).toInt()).coerceIn(scores.indices)
        return maxOf(MIN_ATTENTION_SCORE, scores[index])
    }

    private fun attentionComparator(): Comparator<ScanResult> =
        compareByDescending<ScanResult>(CandidateQualityGate::attentionScore)
            .thenByDescending(ScanResult::anomalyScore)

    private const val MIN_RESULTS = 5
    private const val MAX_RESULTS = 15
    private const val MIN_ATTENTION_SCORE = 5.5
    private const val MARKET_PERCENTILE_FLOOR = 0.35
    private const val MAX_ADAPTIVE_RESULTS = 2
}

internal data class AdaptiveSelection(val results: List<ScanResult>, val adaptiveCount: Int)
