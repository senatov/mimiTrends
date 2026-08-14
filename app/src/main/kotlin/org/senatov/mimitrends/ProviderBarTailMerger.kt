package org.senatov.mimitrends

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketDataSource
import org.senatov.mimitrends.model.MarketObservationQuality
import org.senatov.mimitrends.model.ProviderMinuteBar

internal data class MarketDataSnapshot(
    val historyBars: List<MinuteBar>,
    val analysisBars: List<MinuteBar>,
    val historySource: MarketDataSource,
    val latestSource: MarketDataSource,
    val latestQuality: MarketObservationQuality,
    val latestObservation: ProviderMinuteBar? = null
) {
    val latestEpochSeconds: Long?
        get() = latestObservation?.bar?.minuteEpochSeconds ?: analysisBars.lastOrNull()?.minuteEpochSeconds
    val latestAnalysisEpochSeconds: Long?
        get() = analysisBars.lastOrNull()?.minuteEpochSeconds
}

internal object ProviderBarTailMerger {
    fun merge(
        primary: List<MinuteBar>,
        providerBars: List<ProviderMinuteBar>,
        primarySource: MarketDataSource,
        nowEpochSeconds: Long
    ): MarketDataSnapshot {
        val primaryLast = primary.lastOrNull()?.minuteEpochSeconds ?: Long.MIN_VALUE
        val usable = providerBars.filter {
            it.bar.minuteEpochSeconds > primaryLast &&
                it.bar.minuteEpochSeconds <= nowEpochSeconds &&
                nowEpochSeconds - it.bar.minuteEpochSeconds <= MAX_PROVIDER_AGE_SECONDS
        }
        val latestObservation = usable.maxWithOrNull(
            compareBy<ProviderMinuteBar> { it.bar.minuteEpochSeconds }
                .thenBy { it.observedAtMillis }
                .thenBy { providerRank(it.provider) }
        ) ?: return MarketDataSnapshot(
            primary, primary, primarySource, primarySource, MarketObservationQuality.FULL_OHLCV
        )
        val providerTails = usable.groupBy(ProviderMinuteBar::provider).mapValues { (_, observations) ->
            observations.groupBy { it.bar.minuteEpochSeconds }.values
                .map { sameMinute -> sameMinute.maxBy(ProviderMinuteBar::observedAtMillis) }
                .sortedBy { it.bar.minuteEpochSeconds }
        }
        val statisticallyCurrentTails = providerTails.filterValues { tail ->
            tail.size >= MIN_ANALYTIC_TAIL_BARS && isContinuous(tail) &&
                latestObservation.bar.minuteEpochSeconds - tail.last().bar.minuteEpochSeconds <= MAX_ANALYTIC_LAG_SECONDS
        }
        if (statisticallyCurrentTails.isEmpty()) return MarketDataSnapshot(
            primary, primary, primarySource, MarketDataSource.valueOf(latestObservation.provider),
            MarketObservationQuality.QUOTE_SNAPSHOT, latestObservation
        )
        val selectedProvider = statisticallyCurrentTails.maxWithOrNull(
            compareBy<Map.Entry<String, List<ProviderMinuteBar>>> { it.value.size }
                .thenBy { entry -> entry.value.last().bar.minuteEpochSeconds - entry.value.first().bar.minuteEpochSeconds }
                .thenBy { it.value.last().bar.minuteEpochSeconds }
                .thenBy { it.value.last().observedAtMillis }
                .thenBy { providerRank(it.key) }
        )!!.key
        val tail = providerTails.getValue(selectedProvider).map(ProviderMinuteBar::bar)
        return MarketDataSnapshot(
            historyBars = primary,
            analysisBars = primary + tail,
            historySource = primarySource,
            latestSource = MarketDataSource.valueOf(latestObservation.provider),
            latestQuality = MarketObservationQuality.QUOTE_SNAPSHOT,
            latestObservation = latestObservation
        )
    }

    fun isEuropeanSymbol(symbol: String): Boolean = symbol.substringAfterLast('.', "").uppercase() in EUROPEAN_SUFFIXES

    private val PROVIDER_PRIORITY = listOf(
        "LANG_SCHWARZ", "TRADEGATE", "EURONEXT", "WALLSTREET_ONLINE"
    )
    private fun providerRank(provider: String): Int =
        PROVIDER_PRIORITY.indexOf(provider).let { index -> if (index < 0) Int.MIN_VALUE else -index }
    private fun isContinuous(tail: List<ProviderMinuteBar>): Boolean = tail.zipWithNext().all { (first, second) ->
        second.bar.minuteEpochSeconds - first.bar.minuteEpochSeconds in 60L..MAX_ANALYTIC_GAP_SECONDS
    }
    private val EUROPEAN_SUFFIXES = setOf("DE", "PA", "AS", "MI", "HE")
    private const val MAX_ANALYTIC_LAG_SECONDS = 3 * 60L
    private const val MAX_ANALYTIC_GAP_SECONDS = 2 * 60L
    private const val MIN_ANALYTIC_TAIL_BARS = 5
    private const val MAX_PROVIDER_AGE_SECONDS = 15 * 60L
}
