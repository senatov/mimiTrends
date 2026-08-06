package org.senatov.mimitrends

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.CorporateAction
import org.senatov.mimitrends.db.InstrumentMetadata
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
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
        var source = "SQLITE"
        var metadata: InstrumentMetadata? = null
        var corporateActions = emptyList<CorporateAction>()
        val bars = if (localFresh) cached else {
            source = "YAHOO"
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
        if (source == "SQLITE") repository.loadCompanyProfile(symbol)?.let { profile ->
            metadata = InstrumentMetadata(symbol, profile.name, profile.exchange,
                currency(symbol), MarketTimeZone.forSymbol(symbol).id)
        }
        val declaredStatus = dataStatus(symbol)
        if (declaredStatus == "LIVE") source = "FINNHUB"
        val merged = mergeProviderTail(symbol, completedYahoo, source, now)
        val effectiveStatus = if (merged.source in PROVIDER_SOURCES) merged.source else declaredStatus
        analytics.recordMarketEvaluation(metadata, corporateActions, symbol, merged.source, effectiveStatus, merged.bars)
        val primary = scannerEngine.evaluate(symbol, merged.bars, criteria)?.copy(dataStatus = effectiveStatus)
        val fallback = if (primary != null) emptyList() else RELAXATION_LEVELS.map { factor ->
            scannerEngine.evaluateFallback(symbol, merged.bars, criteria, factor)?.copy(dataStatus = effectiveStatus)
        }
        return ScanEvaluation(primary, fallback)
    }

    fun loadPriorityResult(symbol: String, criteria: ScannerCriteria): ScanResult? {
        if (!org.senatov.mimitrends.scanner.MarketCalendar.isOpen(symbol)) return null
        val priorityCriteria = criteria.copy(
            scanIntervalSeconds = PriorityScanCoordinator.PRIORITY_SCAN_INTERVAL_SECONDS
        )
        val evaluation = loadAndEvaluate(symbol, priorityCriteria)
        return evaluation.primary ?: evaluation.fallback.firstNotNullOfOrNull { it }
    }

    private fun mergeProviderTail(
        symbol: String,
        primary: List<MinuteBar>,
        primarySource: String,
        nowEpochSeconds: Long
    ): MergedMarketBars {
        if (!ProviderBarTailMerger.isEuropeanSymbol(symbol)) return MergedMarketBars(primary, primarySource)
        val from = maxOf(primary.lastOrNull()?.minuteEpochSeconds?.plus(60L) ?: 0L, nowEpochSeconds - 4 * 3_600L)
        val providerBars = PROVIDER_SOURCES.flatMap { provider ->
            repository.loadProviderMinuteBars(provider, symbol, from)
        }
        return ProviderBarTailMerger.merge(primary, providerBars, primarySource, nowEpochSeconds)
    }

    private fun currency(symbol: String) = if (symbol.contains('.')) "EUR" else "USD"

    private companion object {
        val RELAXATION_LEVELS = listOf(0.85, 0.70, 0.55)
        val PROVIDER_SOURCES = listOf("TRADEGATE", "EURONEXT")
    }
}

internal data class ScanEvaluation(val primary: ScanResult?, val fallback: List<ScanResult?>)
