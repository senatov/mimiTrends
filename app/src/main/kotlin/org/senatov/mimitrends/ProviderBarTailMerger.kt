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
    val latestQuality: MarketObservationQuality
) {
    val latestEpochSeconds: Long? get() = analysisBars.lastOrNull()?.minuteEpochSeconds
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
        val selectedProvider = usable.groupBy(ProviderMinuteBar::provider).maxWithOrNull(
            compareBy<Map.Entry<String, List<ProviderMinuteBar>>> { entry ->
                entry.value.maxOf(ProviderMinuteBar::observedAtMillis)
            }.thenBy { entry -> -PROVIDER_PRIORITY.indexOf(entry.key).takeIf { it >= 0 }!! }
        )?.key ?: return MarketDataSnapshot(
            primary, primary, primarySource, primarySource, MarketObservationQuality.FULL_OHLCV
        )
        val tail = usable.asSequence()
            .filter { it.provider == selectedProvider }
            .groupBy { it.bar.minuteEpochSeconds }
            .values
            .map { observations -> observations.maxBy(ProviderMinuteBar::observedAtMillis).bar }
            .sortedBy(MinuteBar::minuteEpochSeconds)
        return MarketDataSnapshot(
            historyBars = primary,
            analysisBars = primary + tail,
            historySource = primarySource,
            latestSource = MarketDataSource.valueOf(selectedProvider),
            latestQuality = MarketObservationQuality.QUOTE_SNAPSHOT
        )
    }

    fun isEuropeanSymbol(symbol: String): Boolean = symbol.substringAfterLast('.', "").uppercase() in EUROPEAN_SUFFIXES

    private val PROVIDER_PRIORITY = listOf("BOERSE_DE", "BNP_PARIBAS", "TRADEGATE", "EURONEXT")
    private val EUROPEAN_SUFFIXES = setOf("DE", "PA", "AS", "MI", "HE")
    private const val MAX_PROVIDER_AGE_SECONDS = 15 * 60L
}
