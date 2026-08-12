package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar

internal class ShortMoveLoader(private val repository: MarketRepository) {
    fun load(symbols: Collection<String>, nowEpochSeconds: Long = java.time.Instant.now().epochSecond): List<ShortMove> {
        val from = nowEpochSeconds - 15 * 60
        val bars = symbols.associateWith { symbol ->
            ShortMoveBarComposer.compose(
                repository.loadMinuteBars(symbol, from),
                repository.loadProviderMinuteBars(symbol, from),
                nowEpochSeconds
            )
        }
        return ShortMoveDetector.rank(bars, nowEpochSeconds)
    }
}

internal object ShortMoveBarComposer {
    private const val MAX_PROVIDER_AGE_SECONDS = 15 * 60L

    fun compose(
        primary: List<MinuteBar>,
        providerBars: List<ProviderMinuteBar>,
        nowEpochSeconds: Long
    ): List<MinuteBar> {
        val byMinute = primary.asSequence()
            .filter { it.minuteEpochSeconds <= nowEpochSeconds }
            .associateByTo(sortedMapOf(), MinuteBar::minuteEpochSeconds)
        providerBars.asSequence()
            .filter { observation ->
                observation.bar.minuteEpochSeconds <= nowEpochSeconds &&
                    nowEpochSeconds - observation.bar.minuteEpochSeconds <= MAX_PROVIDER_AGE_SECONDS
            }
            .groupBy { it.bar.minuteEpochSeconds }
            .forEach { (minute, observations) ->
                byMinute[minute] = observations.maxWith(
                    compareBy<ProviderMinuteBar> { it.observedAtMillis }
                        .thenBy { providerRank(it.provider) }
                ).bar
            }
        return byMinute.values.toList()
    }

    private fun providerRank(provider: String): Int = PROVIDER_PRIORITY.indexOf(provider.uppercase())
        .let { index -> if (index < 0) Int.MIN_VALUE else -index }

    private val PROVIDER_PRIORITY = listOf(
        "LANG_SCHWARZ", "TRADERFOX", "BOERSE_DE", "BNP_PARIBAS", "TRADEGATE", "EURONEXT"
    )
}
