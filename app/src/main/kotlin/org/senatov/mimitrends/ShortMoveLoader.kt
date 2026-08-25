package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.scanner.ResearchFeatureExtractor

internal class ShortMoveLoader(
    private val repository: MarketRepository,
    private val analytics: AnalyticsRepository,
    private val exchangeRates: ExchangeRateService
) {
    fun load(symbols: Collection<String>, nowEpochSeconds: Long = java.time.Instant.now().epochSecond): List<ShortMove> {
        val safetyCalibration = mapOf(
            true to analytics.downsideSafetyCalibration(european = true),
            false to analytics.downsideSafetyCalibration(european = false)
        )
        val bars = symbols.associateWith { symbol ->
            val from = nowEpochSeconds - PATTERN_LOOKBACK_DAYS * 86_400L
            ShortMoveBarComposer.compose(
                repository.loadMinuteBars(symbol, from).map { exchangeRates.convertBar(symbol, it) },
                repository.loadProviderMinuteBars(symbol, from).map { observation ->
                    observation.copy(bar = exchangeRates.convertBar(observation.bar, observation.currency))
                },
                nowEpochSeconds
            )
        }
        val ranked = ShortMoveDetector.rank(bars, nowEpochSeconds, Int.MAX_VALUE).map { move ->
            val aggregatePrices = analytics.loadAggregatedBars(
                move.symbol, TREND_RESOLUTION_MINUTES, nowEpochSeconds - TREND_LOOKBACK_DAYS * 86_400L
            ).map { TrendPrice(it.bucketEpochSeconds, it.close) }
            val recentPrices = bars[move.symbol].orEmpty().map { TrendPrice(it.minuteEpochSeconds, it.close) }
            // Aggregates retain the instrument's source currency while recent bars may be converted for display.
            // Never splice both price levels into one return series; fall back to recent bars only when aggregates
            // do not yet cover enough distinct sessions.
            val trendPrices = if (aggregatePrices.distinctBy { it.epochSeconds / 86_400L }.size >= 4) {
                aggregatePrices
            } else recentPrices
            val trend = MultiHorizonTrendModel.assess(trendPrices)
            val features = ResearchFeatureExtractor.extract(bars[move.symbol].orEmpty())
            val quote = repository.loadLatestProviderQuote(move.symbol, (nowEpochSeconds - 120L) * 1_000L)
            val entry = features?.let { extracted -> EntryQualityModel.assess(EntryQualityInput(
                price = move.close,
                bid = quote?.bid ?: Double.NaN,
                ask = quote?.ask ?: Double.NaN,
                return1mPercent = extracted.return1mPercent,
                return3mPercent = extracted.return3mPercent,
                return5mPercent = extracted.return5mPercent,
                volatility30mPercent = extracted.realizedVolatility30m,
                vwapDistancePercent = extracted.vwapDistancePercent,
                sessionHighDistancePercent = extracted.sessionHighDistancePercent
            )) }
            val safety = if (features != null && entry != null) ShortTermSafetyModel.assess(
                move.symbol, bars[move.symbol].orEmpty(), features, entry, trend?.score, nowEpochSeconds,
                safetyCalibration.getValue(move.symbol.contains('.'))
            ) else null
            move.copy(
                trendScore = trend?.score,
                trendConfidence = trend?.confidence ?: 0,
                trendLabel = trend?.label.orEmpty(),
                trendDetails = trend?.details.orEmpty(),
                entryQualityScore = entry?.score ?: -1,
                entryQualityConfidence = entry?.confidence ?: 0,
                entryQualityLabel = entry?.label ?: "Unavailable",
                entryCooldownMinutes = entry?.cooldownMinutes ?: 0,
                entryQualityDetails = entry?.details.orEmpty(),
                safetyScore = safety?.score ?: -1,
                safetyConfidence = safety?.confidence ?: 0,
                safetyLabel = safety?.label ?: "Unavailable",
                safetyDetails = safety?.details.orEmpty()
            )
        }
        val companyName = { symbol: String -> repository.loadCompanyProfile(symbol)?.name }
        val recent = ShortMoveCompanyRanking.distinct(ranked, MAX_MOVES, companyName)
        val moderate = ShortMoveCompanyRanking.distinct(
            ModeratePositiveCandidateSelector.select(ranked), MAX_MODERATE_CANDIDATES, companyName
        )
        return (recent + moderate).distinctBy(ShortMove::symbol)
    }

    private companion object {
        const val MAX_MOVES = 10
        const val MAX_MODERATE_CANDIDATES = 6
        const val PATTERN_LOOKBACK_DAYS = 30L
        const val TREND_LOOKBACK_DAYS = 370L
        const val TREND_RESOLUTION_MINUTES = 60
    }
}

internal object ModeratePositiveCandidateSelector {
    fun select(ranked: List<ShortMove>): List<ShortMove> = ranked.filter { move ->
        move.pattern == ShortMovePattern.DIRECTIONAL &&
            move.changePercent in MIN_CURRENT_MOVE_PERCENT..MAX_CURRENT_MOVE_PERCENT &&
            move.barCount >= MIN_BARS &&
            move.safetyScore >= MIN_SAFETY_SCORE && move.safetyConfidence >= MIN_SAFETY_CONFIDENCE &&
            move.entryQualityScore >= MIN_ENTRY_QUALITY && move.entryCooldownMinutes == 0
    }.sortedWith(compareByDescending<ShortMove> { it.safetyScore }.thenByDescending { it.safetyConfidence })

    fun positivityPercent(move: ShortMove): Int = move.safetyScore.coerceIn(0, 100)

    private const val MIN_CURRENT_MOVE_PERCENT = -0.80
    private const val MAX_CURRENT_MOVE_PERCENT = 1.75
    private const val MIN_BARS = 3
    private const val MIN_SAFETY_SCORE = 56
    private const val MIN_SAFETY_CONFIDENCE = 50
    private const val MIN_ENTRY_QUALITY = 48
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
        "SCALABLE", "LANG_SCHWARZ", "TRADEGATE", "EURONEXT", "WALLSTREET_ONLINE"
    )
    private const val MAX_LIVE_OVERLAY_SECONDS = 20 * 60L
}
