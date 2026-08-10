package org.senatov.mimitrends

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.db.ResearchBackfillSample
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.scanner.ScannerEngine
import java.time.Instant

internal data class ResearchBackfillResult(val symbols: Int, val samples: Int)

internal class ResearchBackfillService(
    private val marketRepository: MarketRepository,
    private val analytics: AnalyticsRepository,
    private val scanner: ScannerEngine
) {
    fun run(criteria: ScannerCriteria, progress: (completed: Int, total: Int, symbol: String) -> Unit): ResearchBackfillResult {
        val available = marketRepository.listSymbols().toSet()
        val symbols = criteria.symbols.map(String::uppercase).distinct().filter { it in available }
        var samples = 0
        symbols.forEachIndexed { index, symbol ->
            check(!Thread.currentThread().isInterrupted) { "Research backfill cancelled" }
            val bars = marketRepository.loadMinuteBars(symbol, Instant.now().epochSecond - RETENTION_DAYS * 86_400L)
            val replay = HistoricalResearchReplay.replay(symbol, bars, criteria.copy(maxSignalAgeMinutes = 0), ::evaluate)
            analytics.recordResearchBackfill(symbol, replay.map {
                ResearchBackfillSample(it.result, it.features, it.outcomes)
            })
            samples += replay.size
            progress(index + 1, symbols.size, symbol)
        }
        return ResearchBackfillResult(symbols.size, samples)
    }

    private fun evaluate(symbol: String, bars: List<org.senatov.mimitrends.model.MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        scanner.evaluate(symbol, bars, criteria)?.let { return it }
        listOf(0.85, 0.70, 0.55).forEach { factor ->
            scanner.evaluateFallback(symbol, bars, criteria, factor)?.let { return it }
        }
        return scanner.evaluateLongTerm(symbol, bars, criteria)
    }

    private companion object { const val RETENTION_DAYS = 90 }
}
