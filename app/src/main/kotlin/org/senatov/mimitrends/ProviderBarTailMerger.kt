package org.senatov.mimitrends

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar

internal data class MergedMarketBars(val bars: List<MinuteBar>, val source: String)

internal object ProviderBarTailMerger {
    fun merge(
        primary: List<MinuteBar>,
        providerBars: List<ProviderMinuteBar>,
        primarySource: String,
        nowEpochSeconds: Long
    ): MergedMarketBars {
        val primaryLast = primary.lastOrNull()?.minuteEpochSeconds ?: Long.MIN_VALUE
        val usable = providerBars.filter {
            it.bar.minuteEpochSeconds > primaryLast &&
                it.bar.minuteEpochSeconds <= nowEpochSeconds &&
                nowEpochSeconds - it.bar.minuteEpochSeconds <= MAX_PROVIDER_AGE_SECONDS
        }
        val selectedProvider = PROVIDER_PRIORITY.firstOrNull { provider ->
            usable.any { it.provider == provider }
        } ?: return MergedMarketBars(primary, primarySource)
        val tail = usable.asSequence()
            .filter { it.provider == selectedProvider }
            .groupBy { it.bar.minuteEpochSeconds }
            .values
            .map { observations -> observations.maxBy(ProviderMinuteBar::observedAtMillis).bar }
            .sortedBy(MinuteBar::minuteEpochSeconds)
        return MergedMarketBars(primary + tail, selectedProvider)
    }

    fun isEuropeanSymbol(symbol: String): Boolean = symbol.substringAfterLast('.', "").uppercase() in EUROPEAN_SUFFIXES

    private val PROVIDER_PRIORITY = listOf("TRADEGATE", "EURONEXT")
    private val EUROPEAN_SUFFIXES = setOf("DE", "PA", "AS", "MI", "HE")
    private const val MAX_PROVIDER_AGE_SECONDS = 15 * 60L
}
