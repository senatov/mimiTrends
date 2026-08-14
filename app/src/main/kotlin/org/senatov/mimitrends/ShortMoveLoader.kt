package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.scanner.MarketCalendar
import java.time.Instant

internal class ShortMoveLoader(
    private val repository: MarketRepository,
    private val exchangeRates: ExchangeRateService
) {
    fun load(symbols: Collection<String>, nowEpochSeconds: Long = java.time.Instant.now().epochSecond): List<ShortMove> {
        val bars = symbols.associateWith { symbol ->
            val from = MarketCalendar.sessionStart(symbol, Instant.ofEpochSecond(nowEpochSeconds)).epochSecond
            ShortMoveBarComposer.compose(
                repository.loadMinuteBars(symbol, from).map { exchangeRates.convertBar(symbol, it) },
                repository.loadProviderMinuteBars(symbol, from).map { observation ->
                    observation.copy(bar = exchangeRates.convertBar(observation.bar, observation.currency))
                },
                nowEpochSeconds
            )
        }
        return ShortMoveCompanyRanking.distinct(
            ShortMoveDetector.rank(bars, nowEpochSeconds, Int.MAX_VALUE),
            MAX_MOVES
        ) { symbol -> repository.loadCompanyProfile(symbol)?.name }
    }

    private companion object {
        const val MAX_MOVES = 10
    }
}

internal object ShortMoveCompanyRanking {
    fun distinct(
        ranked: List<ShortMove>,
        limit: Int,
        companyName: (String) -> String?
    ): List<ShortMove> = ranked.distinctBy { move ->
        companyName(move.symbol)?.let { CompanySearchTerm.from(it, move.symbol).lowercase() }
            ?: move.symbol.uppercase()
    }.take(limit)
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
        val tails = providerBars.asSequence()
            .filter { it.bar.minuteEpochSeconds in (nowEpochSeconds - MAX_LIVE_OVERLAY_SECONDS)..nowEpochSeconds }
            .groupBy(ProviderMinuteBar::provider)
        val selectedTail = tails.maxWithOrNull(compareBy<Map.Entry<String, List<ProviderMinuteBar>>> {
            it.value.maxOf { observation -> observation.bar.minuteEpochSeconds }
        }.thenBy { it.value.maxOf(ProviderMinuteBar::observedAtMillis) }
            .thenBy { it.value.size }
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

    private fun providerRank(provider: String): Int = PROVIDER_PRIORITY.indexOf(provider.uppercase())
        .let { index -> if (index < 0) Int.MIN_VALUE else -index }

    private val PROVIDER_PRIORITY = listOf(
        "LANG_SCHWARZ", "TRADEGATE", "EURONEXT", "WALLSTREET_ONLINE"
    )
    private const val MAX_LIVE_OVERLAY_SECONDS = 20 * 60L
}
