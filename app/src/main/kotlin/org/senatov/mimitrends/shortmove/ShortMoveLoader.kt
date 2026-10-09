package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.db.CurveCandidateStore
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.RapidMoveSettings
import org.senatov.mimitrends.model.CurveDecision
import org.senatov.mimitrends.model.CurveDirection

internal class ShortMoveLoader(
    private val repository: MarketRepository,
    private val exchangeRates: ExchangeRateService,
    private val curveCandidates: CurveCandidateStore,
    private val curveModel: CurveCandidateModel,
    private val settings: () -> RapidMoveSettings = { RapidMoveSettings() }
) {
    fun load(symbols: Collection<String>, nowEpochSeconds: Long = java.time.Instant.now().epochSecond): List<ShortMove> {
        val bars = symbols.associateWith { symbol ->
            val from = nowEpochSeconds - RADAR_LOOKBACK_HOURS * 3_600L
            ShortMoveBarComposer.compose(
                repository.loadMinuteBars(symbol, from).map { exchangeRates.convertBar(symbol, it) },
                repository.loadProviderMinuteBars(symbol, from).map { observation ->
                    observation.copy(bar = exchangeRates.convertBar(observation.bar, observation.currency))
                },
                nowEpochSeconds
            )
        }
        val confirmed = ShortMoveDetector.rank(bars, nowEpochSeconds, Int.MAX_VALUE, settings()).associateBy(ShortMove::symbol)
        val ranked = bars.mapNotNull { (symbol, history) ->
            val existing = confirmed[symbol]
            if (existing?.pattern == ShortMovePattern.RAPID_CRASH || existing?.pattern == ShortMovePattern.RAPID_RISE) {
                return@mapNotNull existing
            }
            val candidate = CurveCandidateDetector.detect(symbol, history, nowEpochSeconds)
                ?: return@mapNotNull existing
            val decision = curveCandidates.observe(candidate)
            if (decision == CurveDecision.REJECTED) return@mapNotNull existing
            val assessment = if (decision == CurveDecision.PENDING) curveModel.assess(candidate) else CurveAssessment.SHOW
            if (assessment == CurveAssessment.HIDE) return@mapNotNull existing
            val pattern = if (candidate.direction == CurveDirection.RISE) {
                ShortMovePattern.RAPID_RISE
            } else ShortMovePattern.RAPID_CRASH
            ShortMove(
                symbol, candidate.changePercent, candidate.startPrice, candidate.endPrice,
                candidate.anchorEpochSeconds, candidate.endEpochSeconds, candidate.barCount, pattern,
                latestPrice = history.lastOrNull()?.close,
                curveCandidate = candidate,
                reviewDecision = if (assessment == CurveAssessment.SHOW && decision == CurveDecision.PENDING) {
                    CurveDecision.MODEL_APPROVED
                } else decision
            )
        }.sortedWith(compareBy<ShortMove>(::shortMoveAlertPriority).thenByDescending { kotlin.math.abs(it.changePercent) })
        val companyName = { symbol: String -> repository.loadCompanyProfile(symbol)?.name }
        return ShortMoveCompanyRanking.distinct(ranked, MAX_RADAR_RESULTS, companyName)
    }

    private companion object {
        const val MAX_RADAR_RESULTS = 24
        const val RADAR_LOOKBACK_HOURS = 12L
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
            .filter { observation -> consistentWithPrimary(observation.bar, byMinute) }
            .groupBy { it.bar.minuteEpochSeconds }
            .forEach { (minute, observations) ->
                byMinute[minute] = observations.maxWith(
                    compareBy(ProviderMinuteBar::observedAtMillis)
                ).bar
            }
        return byMinute.values.toList()
    }

    private fun consistentWithPrimary(bar: MinuteBar, primary: Map<Long, MinuteBar>): Boolean {
        val reference = primary[bar.minuteEpochSeconds]
            ?: primary.entries.lastOrNull { (minute, _) ->
                minute <= bar.minuteEpochSeconds && bar.minuteEpochSeconds - minute <= MAX_REFERENCE_AGE_SECONDS
            }?.value
            ?: return true
        if (reference.close <= 0.0 || bar.close <= 0.0) return false
        return kotlin.math.abs(bar.close / reference.close - 1.0) <= MAX_PROVIDER_DEVIATION
    }

    private fun providerRank(provider: String): Int = PROVIDER_PRIORITY.indexOf(provider.uppercase())
        .let { index -> if (index < 0) Int.MIN_VALUE else -index }

    private val PROVIDER_PRIORITY = listOf(
        "SCALABLE", "LANG_SCHWARZ", "TRADEGATE", "EURONEXT", "WALLSTREET_ONLINE"
    )
    private const val MAX_LIVE_OVERLAY_SECONDS = 20 * 60L
    private const val MAX_REFERENCE_AGE_SECONDS = 30 * 60L
    private const val MAX_PROVIDER_DEVIATION = 0.25
}
