package org.senatov.mimitrends

import org.senatov.mimitrends.db.ResearchBackfillOutcome
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.scanner.ResearchFeatureExtractor
import java.time.Instant

internal data class HistoricalResearchSample(
    val result: ScanResult?,
    val features: ResearchFeatures,
    val outcomes: List<ResearchBackfillOutcome>
)

internal object HistoricalResearchReplay {
    fun replay(
        symbol: String,
        bars: List<MinuteBar>,
        criteria: ScannerCriteria,
        evaluate: (String, List<MinuteBar>, ScannerCriteria) -> ScanResult?
    ): List<HistoricalResearchSample> {
        val sorted = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        val zone = MarketTimeZone.forSymbol(symbol)
        val sessions = sorted.withIndex().groupBy {
            Instant.ofEpochSecond(it.value.minuteEpochSeconds).atZone(zone).toLocalDate()
        }.toSortedMap()
        val dates = sessions.keys.toList()
        if (dates.size <= BASELINE_SESSIONS) return emptyList()
        return buildList {
            dates.drop(BASELINE_SESSIONS).forEach { date ->
                val indexed = sessions.getValue(date)
                indexed.filterIndexed { offset, _ -> offset % SAMPLE_INTERVAL_MINUTES == 0 }
                    .forEach pointLoop@ { point ->
                        val future = futureOutcomes(sorted, point.index, date, zone)
                        if (future.size != HORIZONS.size) return@pointLoop
                        val cutoff = sorted[point.index].minuteEpochSeconds - CONTEXT_DAYS * 86_400L
                        val contextStart = sorted.indexOfFirst { it.minuteEpochSeconds >= cutoff }.coerceAtLeast(0)
                        val history = sorted.subList(contextStart, point.index + 1)
                        val features = ResearchFeatureExtractor.extract(history) ?: return@pointLoop
                        add(HistoricalResearchSample(evaluate(symbol, history, criteria), features, future))
                    }
            }
        }
    }

    private fun futureOutcomes(
        bars: List<MinuteBar>,
        entryIndex: Int,
        entryDate: java.time.LocalDate,
        zone: java.time.ZoneId
    ): List<ResearchBackfillOutcome> {
        val entry = bars[entryIndex]
        return HORIZONS.mapNotNull { horizon ->
            val minimumEpoch = entry.minuteEpochSeconds + horizon * 60L
            val maximumEpoch = minimumEpoch + MAX_LAG_MINUTES * 60L
            val observedIndex = ((entryIndex + 1)..bars.lastIndex).firstOrNull { index ->
                val bar = bars[index]
                bar.minuteEpochSeconds in minimumEpoch..maximumEpoch &&
                    Instant.ofEpochSecond(bar.minuteEpochSeconds).atZone(zone).toLocalDate() == entryDate
            } ?: return@mapNotNull null
            val observed = bars[observedIndex]
            val path = bars.subList(entryIndex + 1, observedIndex + 1)
            ResearchBackfillOutcome(
                horizonMinutes = horizon,
                observedPrice = observed.close,
                returnPercent = percent(observed.close, entry.close),
                elapsedMinutes = (observed.minuteEpochSeconds - entry.minuteEpochSeconds) / 60.0,
                maximumReturnPercent = path.maxOfOrNull { percent(it.high, entry.close) } ?: 0.0,
                minimumReturnPercent = path.minOfOrNull { percent(it.low, entry.close) } ?: 0.0,
                observedEpochSeconds = observed.minuteEpochSeconds
            )
        }
    }

    private fun percent(value: Double, reference: Double) = (value / reference - 1.0) * 100.0

    private const val BASELINE_SESSIONS = 5
    private const val CONTEXT_DAYS = 10
    private const val SAMPLE_INTERVAL_MINUTES = 15
    private const val MAX_LAG_MINUTES = 4
    private val HORIZONS = listOf(5, 10, 30)
}
