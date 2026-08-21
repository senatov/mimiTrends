package org.senatov.mimitrends

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria

internal data class ScannerBatchResult(
    val active: List<ScanResult>,
    val strictCount: Int,
    val adaptiveCount: Int,
    val errors: List<String>,
    val sourceCoverage: Map<String, Int>,
    val oldestDataAgeSeconds: Long?,
    val reusedAnalyses: Int = 0
)

internal class ScannerBatchService(
    private val evaluate: (String, ScannerCriteria) -> ScanEvaluation,
    private val analytics: AnalyticsRepository,
    private val repository: MarketRepository,
    private val fallbackStatus: (String) -> String
) {
    fun execute(
        symbols: List<String>,
        criteria: ScannerCriteria,
        isCurrent: () -> Boolean,
        onProgress: (completed: Int, symbol: String) -> Unit
    ): ScannerBatchResult? {
        val runId = analytics.beginScan(criteria.marketRegion.name, symbols.size, criteria.scanIntervalSeconds)
        val strict = mutableListOf<ScanResult>()
        val fallbackLevels = List(3) { mutableListOf<ScanResult>() }
        val longTerm = mutableListOf<ScanResult>()
        val contexts = mutableListOf<ScanResult>()
        val errors = mutableListOf<String>()
        val sourceCoverage = linkedMapOf<String, Int>()
        var oldestDataAgeSeconds: Long? = null
        var reusedAnalyses = 0
        val nowEpochSeconds = java.time.Instant.now().epochSecond
        symbols.forEachIndexed { index, symbol ->
            if (!isCurrent()) {
                analytics.abortScan(runId)
                return null
            }
            runCatching { evaluate(symbol, criteria) }
                .onSuccess { evaluation ->
                    if (evaluation.reusedAnalysis) reusedAnalyses++
                    sourceCoverage.compute(evaluation.sourceStatus) { _, count -> (count ?: 0) + 1 }
                    evaluation.latestDataEpochSeconds?.let { latest ->
                        val age = (nowEpochSeconds - latest).coerceAtLeast(0L)
                        oldestDataAgeSeconds = maxOf(oldestDataAgeSeconds ?: 0L, age)
                    }
                    evaluation.primary?.let(strict::add)
                    evaluation.fallback.forEachIndexed { level, result ->
                        result?.let(fallbackLevels[level]::add)
                    }
                    evaluation.longTerm?.let(longTerm::add)
                    evaluation.context?.let(contexts::add)
                    val accepted = evaluation.primary ?: evaluation.fallback.firstNotNullOfOrNull { it }
                        ?: evaluation.longTerm ?: evaluation.context
                    analytics.recordScanCandidate(runId, symbol, accepted,
                        if (accepted == null) evaluation.rejectionReason ?: "NO_CURRENT_SIGNAL" else null,
                        accepted?.dataStatus ?: fallbackStatus(symbol), evaluation.researchFeatures)
                }
                .onFailure { error ->
                    errors += "$symbol: ${error.message ?: error.javaClass.simpleName}"
                    analytics.recordScanCandidate(runId, symbol, null,
                        "ERROR: ${error.message ?: error.javaClass.simpleName}", "UNAVAILABLE")
                }
            onProgress(index + 1, symbol)
        }
        repository.flushPending()
        if (!isCurrent()) {
            analytics.abortScan(runId)
            return null
        }
        val calibratedStrict = strict.map(analytics::withCalibration)
        val calibratedFallbacks = fallbackLevels.map { level -> level.map(analytics::withCalibration) }
        val calibratedLongTerm = longTerm.map(analytics::withCalibration)
        val calibratedContexts = contexts.map(analytics::withCalibration)
        val selection = AdaptiveResultSelector.select(
            calibratedStrict, calibratedFallbacks, criteria.minimumTableResults, criteria.resultLimit,
            calibratedLongTerm, calibratedContexts
        )
        analytics.completeScan(runId, selection.results.map(ScanResult::symbol), errors.size)
        val strictSymbols = calibratedStrict.mapTo(hashSetOf(), ScanResult::symbol)
        val qualifiedStrictCount = selection.results.count { it.symbol in strictSymbols }
        return ScannerBatchResult(selection.results, qualifiedStrictCount, selection.adaptiveCount, errors,
            sourceCoverage.toMap(), oldestDataAgeSeconds, reusedAnalyses)
    }
}
