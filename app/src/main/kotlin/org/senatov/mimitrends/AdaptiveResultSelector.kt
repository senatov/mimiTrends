package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

internal object AdaptiveResultSelector {
    fun select(
        strict: Collection<ScanResult>,
        fallbackLevels: List<Collection<ScanResult>>,
        requestedTarget: Int,
        requestedLimit: Int,
        longTerm: Collection<ScanResult> = emptyList(),
        contexts: Collection<ScanResult> = emptyList()
    ): AdaptiveSelection {
        val limit = requestedLimit.coerceIn(MIN_RESULTS, MAX_RESULTS)
        val target = minOf(maxOf(requestedTarget, MIN_TARGET_RESULTS), TARGET_RESULTS, limit)
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
        val watchCandidates = (strict.asSequence() + fallbackLevels.asSequence().flatten())
            .filter { it.signalSource.contains("watch") }
            .filter(CandidateQualityGate::qualifiesWatch)
            .filter { CandidateQualityGate.attentionScore(it) >= MIN_WATCH_ATTENTION_SCORE }
            .sortedWith(attentionComparator())
        watchCandidates.forEach { candidate ->
            if (selected.size < target) selected.putIfAbsent(candidate.symbol, candidate)
        }
        longTerm.asSequence()
            .filter(CandidateQualityGate::qualifiesLongTerm)
            .sortedWith(attentionComparator())
            .forEach { candidate ->
                if (selected.size < target) selected.putIfAbsent(candidate.symbol, candidate)
            }
        contexts.asSequence()
            .filter(CandidateQualityGate::qualifiesContext)
            .sortedWith(attentionComparator())
            .forEach { candidate ->
                if (selected.size < target) selected.putIfAbsent(candidate.symbol, candidate)
            }
        val results = selected.values.sortedWith(attentionComparator()).take(limit)
        val adaptiveCount = results.count {
            it.signalSource.contains("relaxed") || it.signalSource.startsWith("Trend") ||
                it.signalSource.startsWith("Steady rise") || it.signalSource.startsWith("Recovery") ||
                it.signalSource.contains("watch") || it.signalSource.contains("context", ignoreCase = true)
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
        compareBy<ScanResult>(CandidateQualityGate::priorityTier)
            .thenByDescending(CandidateQualityGate::attentionScore)
            .thenByDescending(ScanResult::anomalyScore)

    private const val MIN_RESULTS = 5
    private const val MIN_TARGET_RESULTS = 7
    private const val TARGET_RESULTS = 8
    private const val MAX_RESULTS = 15
    private const val MIN_ATTENTION_SCORE = 4.5
    private const val MARKET_PERCENTILE_FLOOR = 0.35
    private const val MAX_ADAPTIVE_RESULTS = 2
    private const val MIN_WATCH_ATTENTION_SCORE = 3.0
}

internal data class AdaptiveSelection(val results: List<ScanResult>, val adaptiveCount: Int)
