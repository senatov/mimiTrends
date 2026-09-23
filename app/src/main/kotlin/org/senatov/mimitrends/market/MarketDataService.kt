package org.senatov.mimitrends.market

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.MarketDataSource
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.slf4j.LoggerFactory

internal class MarketDataService(
    private val repository: MarketRepository,
    private val yahooFinance: YahooFinanceClient,
    private val dataStatus: (String) -> String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun closedMarketSnapshot(symbols: List<String>, criteria: ScannerCriteria): List<ScanResult> = emptyList()

    fun loadAndEvaluate(symbol: String, criteria: ScannerCriteria): ScanEvaluation {
        val now = java.time.Instant.now().epochSecond
        val cached = repository.loadMinuteBars(symbol, now - 7 * 86_400)
        val latestLocal = cached.lastOrNull()?.minuteEpochSeconds
        val needsBootstrap = cached.map { it.minuteEpochSeconds / 86_400L }.distinct().size < 2
        val localFresh = !needsBootstrap && latestLocal != null && latestLocal >= now - criteria.scanIntervalSeconds
        var source = MarketDataSource.SQLITE
        val bars = if (localFresh) cached else {
            source = MarketDataSource.YAHOO
            val incrementalAfter = if (needsBootstrap) null else latestLocal?.takeIf { it >= now - 7 * 86_400 }
            val series = yahooFinance.loadIntraday(symbol, incrementalAfter)
            series.bars.forEach(repository::upsertMinuteBar)
            val oldProfile = repository.loadCompanyProfile(symbol)
            repository.upsertCompanyProfile(
                CompanyProfile(
                    symbol, series.companyName, series.exchange,
                    oldProfile?.logoUrl, oldProfile?.logoBytes, System.currentTimeMillis()
                )
            )
            repository.loadMinuteBars(symbol, now - 30 * 86_400)
        }
        val analysisInput = currentAnalysisBars(bars, source, now)
        val declaredStatus = dataStatus(symbol)
        if (declaredStatus == "LIVE") source = MarketDataSource.FINNHUB
        val merged = mergeProviderTail(symbol, analysisInput, source, now)
        val effectiveStatus = if (merged.latestQuality == org.senatov.mimitrends.model.MarketObservationQuality.QUOTE_SNAPSHOT)
            merged.latestSource.name else declaredStatus
        val monitored = monitoredResult(symbol, merged, effectiveStatus, now)
        if (!OpenMarketDataFreshness.isUsable(merged.latestAnalysisEpochSeconds, now)) {
            log.debug(
                LogTag.API, "open-market data rejected as stale symbol={} latest={} now={}",
                symbol, merged.latestAnalysisEpochSeconds, now
            )
            return ScanEvaluation(
                null, emptyList(), "STALE_DATA", sourceStatus = merged.latestSource.name,
                latestDataEpochSeconds = merged.latestAnalysisEpochSeconds, monitored = monitored
            )
        }
        if (!merged.analysisTracksLatestQuote()) {
            log.debug(
                LogTag.API, "open-market analysis rejected behind quote symbol={} analysis={} quote={}",
                symbol, merged.latestAnalysisEpochSeconds, merged.latestEpochSeconds
            )
            return ScanEvaluation(
                null, emptyList(), "ANALYSIS_BEHIND_QUOTE", sourceStatus = merged.latestSource.name,
                latestDataEpochSeconds = merged.latestAnalysisEpochSeconds, monitored = monitored
            )
        }
        val move = ShortMoveDetector.rank(mapOf(symbol to merged.analysisBars), now, limit = 1).firstOrNull()
        val primary = move?.toScanResult(merged.analysisBars, effectiveStatus, now)
        return ScanEvaluation(
            primary = primary,
            fallback = emptyList(),
            rejectionReason = if (primary == null) "NO_CORRIDOR_OR_RAPID_CRASH" else null,
            sourceStatus = merged.latestSource.name,
            latestDataEpochSeconds = merged.latestAnalysisEpochSeconds,
            monitored = monitored
        )
    }

    private fun monitoredResult(
        symbol: String,
        snapshot: MarketDataSnapshot,
        status: String,
        nowEpochSeconds: Long
    ): ScanResult? {
        val bars = snapshot.analysisBars.sortedBy(MinuteBar::minuteEpochSeconds)
        val latest = bars.lastOrNull() ?: return null
        val anchor = bars.lastOrNull { it.minuteEpochSeconds <= latest.minuteEpochSeconds - 10 * 60L } ?: bars.first()
        val recent = bars.filter { it.minuteEpochSeconds >= latest.minuteEpochSeconds - SESSION_ACTIVITY_HOURS * 3_600L }
        val result = ScanResult(
            symbol = symbol,
            price = snapshot.latestObservation?.bar?.close ?: latest.close,
            anomalyScore = 0.0,
            priceAnomaly = Double.NaN,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = 0.0,
            windowChangePercent = if (anchor.close > 0.0) (latest.close / anchor.close - 1.0) * 100.0 else 0.0,
            windowVolume = latest.volume,
            sessionVolume = recent.sumOf(MinuteBar::volume),
            sessionTurnover = recent.sumOf { it.close * it.volume },
            signalAgeMinutes = 0,
            signalSource = "Pinned · monitoring",
            updatedAtMillis = snapshot.latestObservation?.observedAtMillis ?: (latest.minuteEpochSeconds * 1_000L),
            dataStatus = status,
            signalWindowLabel = "watchlist",
            signalPrice = latest.close,
            signalEpochMillis = latest.minuteEpochSeconds * 1_000L,
            analysisUpdatedAtMillis = (snapshot.latestAnalysisEpochSeconds ?: latest.minuteEpochSeconds) * 1_000L,
            scanEvaluatedAtMillis = nowEpochSeconds * 1_000L
        )
        return result.withRecentDynamics(bars).withExecutableQuote(nowEpochSeconds)
    }

    fun loadPriorityResult(symbol: String, criteria: ScannerCriteria): ScanResult? {
        if (!org.senatov.mimitrends.scanner.MarketCalendar.isOpen(symbol)) return null
        val priorityCriteria = criteria.copy(
            // A user-initiated selection is an explicit refresh, not a cache read.
            scanIntervalSeconds = 0
        )
        val evaluation = loadAndEvaluate(symbol, priorityCriteria)
        return evaluation.primary
    }

    private fun ShortMove.toScanResult(
        bars: List<MinuteBar>,
        status: String,
        nowEpochSeconds: Long
    ): ScanResult {
        val recent = bars.filter { it.minuteEpochSeconds >= nowEpochSeconds - SESSION_ACTIVITY_HOURS * 3_600L }
        val ageMinutes = ((nowEpochSeconds - endedAtEpochSeconds).coerceAtLeast(0L) / 60L).toInt()
        val score = when (pattern) {
            ShortMovePattern.RAPID_CRASH -> 100.0
            ShortMovePattern.TRADABLE_CORRIDOR -> opportunityScore.coerceAtLeast(0).toDouble()
        }
        return ScanResult(
            symbol = symbol,
            price = close,
            anomalyScore = score,
            priceAnomaly = Double.NaN,
            volumeAnomaly = Double.NaN,
            rangeAnomaly = Double.NaN,
            relativeVolume = Double.NaN,
            candleBodyRatio = 0.0,
            windowChangePercent = changePercent,
            windowVolume = recent.takeLast(barCount).sumOf(MinuteBar::volume),
            sessionVolume = recent.sumOf(MinuteBar::volume),
            sessionTurnover = recent.sumOf { it.close * it.volume },
            signalAgeMinutes = ageMinutes,
            signalSource = if (pattern == ShortMovePattern.RAPID_CRASH) "Rapid crash" else "Tradable corridor",
            updatedAtMillis = endedAtEpochSeconds * 1_000L,
            dataStatus = status,
            signalWindowLabel = if (pattern == ShortMovePattern.RAPID_CRASH) "4m" else "120m corridor",
            signalPrice = open,
            signalEpochMillis = eventEpochSeconds * 1_000L,
            analysisUpdatedAtMillis = endedAtEpochSeconds * 1_000L,
            scanEvaluatedAtMillis = nowEpochSeconds * 1_000L
        ).withExecutableQuote(nowEpochSeconds)
    }

    private fun mergeProviderTail(
        symbol: String,
        primary: List<MinuteBar>,
        primarySource: MarketDataSource,
        nowEpochSeconds: Long
    ): MarketDataSnapshot {
        if (!ProviderBarTailMerger.isEuropeanSymbol(symbol)) return MarketDataSnapshot(
            primary, primary, primarySource, primarySource,
            org.senatov.mimitrends.model.MarketObservationQuality.FULL_OHLCV
        )
        val providerBars = repository.loadProviderMinuteBars(symbol, nowEpochSeconds - PROVIDER_LOOKBACK_SECONDS)
        return ProviderBarTailMerger.merge(primary, providerBars, primarySource, nowEpochSeconds)
    }

    private fun ScanResult.forPresentation(
        snapshot: MarketDataSnapshot,
        status: String,
        nowEpochSeconds: Long
    ): ScanResult {
        val analysisEpoch = snapshot.latestAnalysisEpochSeconds ?: (updatedAtMillis / 1_000L)
        val actualSignalAgeMinutes = ((nowEpochSeconds - signalEpochMillis / 1_000L).coerceAtLeast(0L) / 60L).toInt()
        val observation = snapshot.latestObservation ?: return copy(
            signalAgeMinutes = actualSignalAgeMinutes,
            analysisUpdatedAtMillis = analysisEpoch * 1_000L,
            scanEvaluatedAtMillis = nowEpochSeconds * 1_000L,
            dataStatus = status
        )
        return copy(
            price = observation.bar.close,
            signalAgeMinutes = actualSignalAgeMinutes,
            updatedAtMillis = observation.observedAtMillis,
            analysisUpdatedAtMillis = analysisEpoch * 1_000L,
            scanEvaluatedAtMillis = nowEpochSeconds * 1_000L,
            dataStatus = status
        )
    }

    private fun ScanResult.withExecutableQuote(nowEpochSeconds: Long): ScanResult {
        val notBefore = (nowEpochSeconds - EXECUTABLE_QUOTE_MAX_AGE_SECONDS) * 1_000L
        val provider = dataStatus.uppercase().takeIf { it in EXECUTABLE_PROVIDERS }
        val quote = provider?.let { repository.loadLatestProviderQuote(it, symbol, notBefore) }
            ?: repository.loadLatestProviderQuote(symbol, notBefore)
            ?: return this
        val bid = quote.bid?.takeIf { it > 0.0 } ?: return this
        val ask = quote.ask?.takeIf { it >= bid } ?: return this
        return copy(bidPrice = bid, askPrice = ask, executableQuoteAtMillis = quote.observedAtMillis)
    }

    private fun ScanResult.withRecentDynamics(bars: List<MinuteBar>): ScanResult = RecentPriceDynamics.apply(this, bars)

    private companion object {
        const val SESSION_ACTIVITY_HOURS = 8L
        const val EXECUTABLE_QUOTE_MAX_AGE_SECONDS = 2 * 60L
        const val PROVIDER_LOOKBACK_SECONDS = 4 * 3_600L
        val EXECUTABLE_PROVIDERS = MarketDataSource.entries
            .filterNot { it == MarketDataSource.SQLITE || it == MarketDataSource.YAHOO || it == MarketDataSource.FINNHUB }
            .mapTo(hashSetOf()) { it.name }
    }
}

internal fun currentAnalysisBars(
    bars: List<MinuteBar>,
    source: MarketDataSource,
    nowEpochSeconds: Long
): List<MinuteBar> {
    if (source == MarketDataSource.FINNHUB) return bars
    val lastCompletedMinute = nowEpochSeconds / 60L * 60L - 60L
    return bars.filter { it.minuteEpochSeconds <= lastCompletedMinute }
}

internal data class ScanEvaluation(
    val primary: ScanResult?,
    val fallback: List<ScanResult?>,
    val rejectionReason: String? = null,
    val longTerm: ScanResult? = null,
    val researchFeatures: ResearchFeatures? = null,
    val sourceStatus: String = "UNKNOWN",
    val latestDataEpochSeconds: Long? = null,
    val context: ScanResult? = null,
    val reusedAnalysis: Boolean = false,
    val monitored: ScanResult? = null
)
