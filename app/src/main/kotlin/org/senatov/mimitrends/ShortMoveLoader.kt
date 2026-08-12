package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.scanner.MarketCalendar
import java.time.Instant

internal class ShortMoveLoader(private val repository: MarketRepository) {
    fun load(symbols: Collection<String>, nowEpochSeconds: Long = java.time.Instant.now().epochSecond): List<ShortMove> {
        val bars = symbols.associateWith { symbol ->
            val from = MarketCalendar.sessionStart(symbol, Instant.ofEpochSecond(nowEpochSeconds)).epochSecond
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
    fun compose(
        primary: List<MinuteBar>,
        providerBars: List<ProviderMinuteBar>,
        nowEpochSeconds: Long
    ): List<MinuteBar> {
        val byMinute = primary.asSequence()
            .filter { it.minuteEpochSeconds <= nowEpochSeconds }
            .associateByTo(sortedMapOf(), MinuteBar::minuteEpochSeconds)
        val primaryLast = byMinute.lastKeyOrNull() ?: Long.MIN_VALUE
        val tails = providerBars.asSequence()
            .filter { it.bar.minuteEpochSeconds in (primaryLast + 1)..nowEpochSeconds }
            .groupBy(ProviderMinuteBar::provider)
        val selectedTail = tails.maxWithOrNull(compareBy<Map.Entry<String, List<ProviderMinuteBar>>> {
            it.value.maxOf { observation -> observation.bar.minuteEpochSeconds }
        }.thenBy { it.value.maxOf(ProviderMinuteBar::observedAtMillis) }
            .thenBy { providerRank(it.key) })?.value.orEmpty()
        selectedTail.asSequence()
            .groupBy { it.bar.minuteEpochSeconds }
            .forEach { (minute, observations) ->
                byMinute[minute] = observations.maxWith(
                    compareBy(ProviderMinuteBar::observedAtMillis)
                ).bar
            }
        return byMinute.values.toList()
    }

    private fun <V> java.util.SortedMap<Long, V>.lastKeyOrNull(): Long? = if (isEmpty()) null else lastKey()

    private fun providerRank(provider: String): Int = PROVIDER_PRIORITY.indexOf(provider.uppercase())
        .let { index -> if (index < 0) Int.MIN_VALUE else -index }

    private val PROVIDER_PRIORITY = listOf(
        "LANG_SCHWARZ", "TRADERFOX", "BOERSE_DE", "BNP_PARIBAS", "TRADEGATE", "EURONEXT"
    )
}
