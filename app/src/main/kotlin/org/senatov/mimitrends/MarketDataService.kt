package org.senatov.mimitrends

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.CorporateAction
import org.senatov.mimitrends.db.InstrumentMetadata
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MarketDataSource
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.scanner.ResearchFeatureExtractor
import org.senatov.mimitrends.scanner.ScannerEngine
import org.slf4j.LoggerFactory

internal class MarketDataService(
    private val repository: MarketRepository,
    private val analytics: AnalyticsRepository,
    private val scannerEngine: ScannerEngine,
    private val yahooFinance: YahooFinanceClient,
    private val dataStatus: (String) -> String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val analysisCache = MarketAnalysisCache()

    fun backfillCachedAnalytics() {
        val from = java.time.Instant.now().epochSecond - 90 * 86_400L
        val symbols = repository.listSymbols()
        log.info(LogTag.DB, "analytics backfill started symbols={}", symbols.size)
        symbols.forEach { symbol ->
            runCatching {
                val bars = repository.loadMinuteBars(symbol, from)
                if (bars.isNotEmpty()) {
                    repository.loadCompanyProfile(symbol)?.let { profile -> analytics.upsertInstrument(InstrumentMetadata(
                        symbol, profile.name, profile.exchange, currency(symbol), MarketTimeZone.forSymbol(symbol).id
                    )) }
                    analytics.refreshDerived(symbol, bars, "SQLITE_BACKFILL")
                    analytics.recordDataQuality(symbol, "SQLITE_BACKFILL", "CACHE", bars.last().minuteEpochSeconds, bars.size)
                }
            }.onFailure { error -> log.warn(LogTag.DB, "analytics backfill failed symbol={}", symbol, error) }
        }
        log.info(LogTag.DB, "analytics backfill completed symbols={}", symbols.size)
    }

    fun closedMarketSnapshot(symbols: List<String>, criteria: ScannerCriteria): List<ScanResult> {
        val now = java.time.Instant.now().epochSecond
        return symbols.mapNotNull { symbol ->
            runCatching {
                val bars = repository.loadMinuteBars(symbol, now - 30 * 86_400L)
                val result = scannerEngine.evaluate(symbol, bars, criteria)
                    ?: scannerEngine.evaluateFallback(symbol, bars, criteria)
                    ?: return@runCatching null
                val age = ((now - result.updatedAtMillis / 1_000L) / 60L).toInt().coerceAtLeast(1)
                result.copy(signalAgeMinutes = age, dataStatus = "CLOSED CACHE",
                    signalWindowLabel = "${result.signalWindowLabel} saved")
            }.getOrNull()
        }.sortedByDescending(ScanResult::anomalyScore).take(criteria.resultLimit)
    }

    fun ensureCachedInstrumentMetadata() {
        repository.listSymbols().forEach { symbol ->
            val profile = repository.loadCompanyProfile(symbol)
            analytics.upsertInstrument(InstrumentMetadata(
                symbol = symbol,
                name = profile?.name ?: symbol,
                exchange = profile?.exchange ?: if (symbol.contains('.')) "EUROPE" else "US",
                currency = currency(symbol),
                timezone = MarketTimeZone.forSymbol(symbol).id,
                aliases = symbol.substringBefore('.').takeIf { it != symbol }
            ))
        }
    }

    fun loadAndEvaluate(symbol: String, criteria: ScannerCriteria): ScanEvaluation {
        val now = java.time.Instant.now().epochSecond
        val cached = repository.loadMinuteBars(symbol, now - 7 * 86_400)
        val latestLocal = cached.lastOrNull()?.minuteEpochSeconds
        val needsBootstrap = cached.map { it.minuteEpochSeconds / 86_400L }.distinct().size < 2
        val localFresh = !needsBootstrap && latestLocal != null && latestLocal >= now - criteria.scanIntervalSeconds
        var source = MarketDataSource.SQLITE
        var metadata: InstrumentMetadata? = null
        var corporateActions = emptyList<CorporateAction>()
        val bars = if (localFresh) cached else {
            source = MarketDataSource.YAHOO
            val incrementalAfter = if (needsBootstrap) null else latestLocal?.takeIf { it >= now - 7 * 86_400 }
            val series = yahooFinance.loadIntraday(symbol, incrementalAfter)
            series.bars.forEach(repository::upsertMinuteBar)
            val oldProfile = repository.loadCompanyProfile(symbol)
            repository.upsertCompanyProfile(CompanyProfile(symbol, series.companyName, series.exchange,
                oldProfile?.logoUrl, oldProfile?.logoBytes, System.currentTimeMillis()))
            metadata = InstrumentMetadata(symbol, series.companyName, series.exchange,
                series.currency.ifBlank { currency(symbol) }, MarketTimeZone.forSymbol(symbol).id)
            corporateActions = series.events.map { event -> CorporateAction(
                symbol, event.type, event.epochSeconds, event.ratio, event.amount, event.currency, "YAHOO"
            ) }
            repository.loadMinuteBars(symbol, now - 30 * 86_400)
        }
        val completedYahoo = bars.filter { it.minuteEpochSeconds <= now / 60L * 60L - 60L }
        if (source == MarketDataSource.SQLITE) repository.loadCompanyProfile(symbol)?.let { profile ->
            metadata = InstrumentMetadata(symbol, profile.name, profile.exchange,
                currency(symbol), MarketTimeZone.forSymbol(symbol).id)
        }
        val declaredStatus = dataStatus(symbol)
        if (declaredStatus == "LIVE") source = MarketDataSource.FINNHUB
        val merged = mergeProviderTail(symbol, completedYahoo, source, now)
        val effectiveStatus = if (merged.latestQuality == org.senatov.mimitrends.model.MarketObservationQuality.QUOTE_SNAPSHOT)
            merged.latestSource.name else declaredStatus
        analytics.recordMarketEvaluation(metadata, corporateActions, symbol, merged.historySource.name,
            effectiveStatus, merged.historyBars, merged.latestEpochSeconds, merged.latestSource.name,
            merged.latestQuality)
        if (!OpenMarketDataFreshness.isUsable(merged.latestAnalysisEpochSeconds, now)) {
            log.debug(LogTag.API, "open-market data rejected as stale symbol={} latest={} now={}",
                symbol, merged.latestAnalysisEpochSeconds, now)
            return ScanEvaluation(null, emptyList(), "STALE_DATA", sourceStatus = merged.latestSource.name,
                latestDataEpochSeconds = merged.latestAnalysisEpochSeconds)
        }
        if (!merged.analysisTracksLatestQuote()) {
            log.debug(LogTag.API, "open-market analysis rejected behind quote symbol={} analysis={} quote={}",
                symbol, merged.latestAnalysisEpochSeconds, merged.latestEpochSeconds)
            return ScanEvaluation(null, emptyList(), "ANALYSIS_BEHIND_QUOTE", sourceStatus = merged.latestSource.name,
                latestDataEpochSeconds = merged.latestAnalysisEpochSeconds)
        }
        analysisCache.reuse(symbol, merged.analysisBars, criteria, now * 1_000L)?.let { return it }
        val researchFeatures = ResearchFeatureExtractor.extract(merged.analysisBars)
        val prepare: (ScanResult) -> ScanResult = { result ->
            result.forPresentation(merged, effectiveStatus, now)
                .withRecentDynamics(merged.analysisBars)
                .withExecutableQuote(now)
                .withEntryQuality(researchFeatures)
        }
        val primary = scannerEngine.evaluate(symbol, merged.analysisBars, criteria)?.let(prepare)
        val fallback = if (primary != null) emptyList() else RELAXATION_LEVELS.map { factor ->
            scannerEngine.evaluateFallback(symbol, merged.analysisBars, criteria, factor)?.let(prepare)
        }
        val longTerm = scannerEngine.evaluateLongTerm(symbol, merged.analysisBars, criteria)?.let(prepare)
        val context = scannerEngine.evaluateContext(symbol, merged.analysisBars, criteria)?.let(prepare)
        val rejectionReason = if (primary == null && fallback.none { it != null } && longTerm == null && context == null) {
            scannerEngine.rejectionReason(merged.analysisBars)
        } else null
        return ScanEvaluation(primary, fallback, rejectionReason, longTerm,
            researchFeatures, merged.latestSource.name,
            merged.latestAnalysisEpochSeconds, context).also {
            analysisCache.record(symbol, merged.analysisBars, criteria, it)
        }
    }

    fun loadPriorityResult(symbol: String, criteria: ScannerCriteria): ScanResult? {
        if (!org.senatov.mimitrends.scanner.MarketCalendar.isOpen(symbol)) return null
        val priorityCriteria = criteria.copy(
            scanIntervalSeconds = PriorityScanCoordinator.PRIORITY_SCAN_INTERVAL_SECONDS
        )
        val evaluation = loadAndEvaluate(symbol, priorityCriteria)
        return evaluation.primary ?: evaluation.fallback.firstNotNullOfOrNull { it } ?: evaluation.longTerm
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
        return ProviderBarTailMerger.merge(primary, emptyList(), primarySource, nowEpochSeconds)
    }

    private fun currency(symbol: String) = if (symbol.contains('.')) "EUR" else "USD"

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
        val quote = repository.loadLatestProviderQuote(symbol, (nowEpochSeconds - EXECUTABLE_QUOTE_MAX_AGE_SECONDS) * 1_000L)
            ?: return this
        val bid = quote.bid?.takeIf { it > 0.0 } ?: return this
        val ask = quote.ask?.takeIf { it >= bid } ?: return this
        return copy(bidPrice = bid, askPrice = ask, executableQuoteAtMillis = quote.observedAtMillis)
    }

    private fun ScanResult.withRecentDynamics(bars: List<MinuteBar>): ScanResult = RecentPriceDynamics.apply(this, bars)

    private fun ScanResult.withEntryQuality(features: ResearchFeatures?): ScanResult {
        if (features == null) return this
        val assessment = EntryQualityModel.assess(EntryQualityModel.input(this, features))
        return copy(
            entryQualityScore = assessment.score,
            entryQualityConfidence = assessment.confidence,
            entryQualityLabel = assessment.label,
            entryCooldownMinutes = assessment.cooldownMinutes,
            entryQualityDetails = assessment.details
        )
    }

    private companion object {
        val RELAXATION_LEVELS = listOf(0.85, 0.70, 0.55)
        const val EXECUTABLE_QUOTE_MAX_AGE_SECONDS = 2 * 60L
    }
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
    val reusedAnalysis: Boolean = false
)
