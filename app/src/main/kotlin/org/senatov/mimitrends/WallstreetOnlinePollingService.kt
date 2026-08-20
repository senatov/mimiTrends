package org.senatov.mimitrends

import javafx.application.Platform
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.WallstreetOnlineMarketDataClient
import org.senatov.mimitrends.marketdata.WallstreetOnlineMover
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.senatov.mimitrends.model.VolumeStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

internal class WallstreetOnlinePollingService(
    private val repository: MarketRepository,
    private val observationSink: MarketObservationSink,
    private val client: WallstreetOnlineMarketDataClient = WallstreetOnlineMarketDataClient()
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-wallstreet-online-provider").apply { isDaemon = true }
    }
    private var symbols = emptyList<String>()
    private var generation = 0L
    private var task: ScheduledFuture<*>? = null
    private val rejectedCandidates = RejectedProviderCandidates()

    @Synchronized
    fun replaceSymbols(values: Collection<String>) {
        symbols = values.map(String::uppercase).filter(ProviderBarTailMerger::isEuropeanSymbol).distinct()
        generation++
        task?.cancel(false)
        task = null
        if (symbols.isNotEmpty()) schedule(0L, generation)
        log.info(LogTag.API, "wallstreetONLINE provider configured symbols={}", symbols.size)
    }

    private fun poll(expectedGeneration: Long) {
        try {
            val targets = synchronized(this) {
                if (generation != expectedGeneration || symbols.isEmpty()) return
                symbols
            }
            val movers = client.loadMovers()
            targets.mapNotNull { symbol -> match(symbol, movers)?.let { symbol to it } }
                .sortedByDescending { (_, mover) -> abs(mover.changePercent) }
                .take(MAX_DETAIL_REQUESTS)
                .forEach { (symbol, mover) -> refresh(symbol, mover) }
        } catch (error: Exception) {
            if (error !is InterruptedException) {
                log.warn(LogTag.API, "wallstreetONLINE crawl failed operation=poll", error)
            }
        } finally {
            synchronized(this) {
                if (generation == expectedGeneration && symbols.isNotEmpty()) schedule(POLL_INTERVAL_MILLIS, generation)
            }
        }
    }

    private fun match(symbol: String, movers: List<WallstreetOnlineMover>): WallstreetOnlineMover? {
        val name = repository.loadCompanyProfile(symbol)?.name ?: return null
        val expectedIsin = repository.loadInstrumentIsin(symbol) ?: return null
        return movers.firstOrNull {
            ProviderInstrumentSelector.matchesCompany(symbol, name, it.name) &&
                !rejectedCandidates.contains(symbol, expectedIsin, it.path)
        }
    }

    private fun refresh(symbol: String, mover: WallstreetOnlineMover) {
        val expectedIsin = repository.loadInstrumentIsin(symbol) ?: return
        val quote = client.loadQuote(mover.path)
        if (!quote.isin.equals(expectedIsin, ignoreCase = true)) {
            if (rejectedCandidates.reject(symbol, expectedIsin, mover.path)) {
                log.warn(LogTag.API,
                    "wallstreetONLINE candidate rejected symbol={} expectedIsin={} actualIsin={} path={}",
                    symbol, expectedIsin, quote.isin, mover.path)
            }
            return
        }
        val now = System.currentTimeMillis()
        if (quote.last <= 0.0 || quote.observedAtMillis !in (now - MAX_QUOTE_AGE_MILLIS)..(now + FUTURE_TOLERANCE_MILLIS)) return
        val mic = VENUE_MICS[quote.venue.lowercase()] ?: "WSOO"
        repository.upsertProviderInstrument(ProviderInstrument(
            PROVIDER, symbol, quote.isin, mic, quote.currency, quote.name, now
        ))
        repository.upsertProviderQuote(ProviderQuoteSnapshot(
            PROVIDER, symbol, quote.isin, quote.currency, quote.last, quote.bid, quote.ask,
            null, null, null, null, null, null, null, null, null, quote.observedAtMillis
        ))
        val minute = quote.observedAtMillis / 60_000L * 60L
        val bar = MinuteBar(symbol, minute, quote.last, quote.last, quote.last, quote.last, 0.0, VolumeStatus.MISSING)
        val observation = ProviderMinuteBar(
            PROVIDER, symbol, quote.isin, mic, quote.currency, bar, quote.observedAtMillis
        )
        if (repository.upsertProviderMinuteBar(observation)) observationSink.publish(observation)
    }

    private fun schedule(delayMillis: Long, expectedGeneration: Long) {
        task = scheduler.schedule({ poll(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        synchronized(this) { generation++; task?.cancel(false); task = null; symbols = emptyList() }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val PROVIDER = "WALLSTREET_ONLINE"
        const val POLL_INTERVAL_MILLIS = 30_000L
        const val MAX_QUOTE_AGE_MILLIS = 15 * 60_000L
        const val FUTURE_TOLERANCE_MILLIS = 60_000L
        const val MAX_DETAIL_REQUESTS = 5
        val VENUE_MICS = mapOf("tradegate" to "XGAT", "lang & schwarz" to "LSSI", "xetra" to "XETR")
    }
}

internal class RejectedProviderCandidates {
    private val values = mutableSetOf<CandidateIdentity>()

    @Synchronized
    fun reject(symbol: String, expectedIsin: String, path: String): Boolean =
        values.add(CandidateIdentity(symbol.uppercase(), expectedIsin.uppercase(), path))

    @Synchronized
    fun contains(symbol: String, expectedIsin: String, path: String): Boolean =
        CandidateIdentity(symbol.uppercase(), expectedIsin.uppercase(), path) in values

    private data class CandidateIdentity(val symbol: String, val expectedIsin: String, val path: String)
}

internal class StockPageOpener(
    private val repository: MarketRepository,
    private val client: WallstreetOnlineMarketDataClient,
    private val searchUrl: () -> String,
    private val openExternal: (String) -> Unit,
    private val setStatus: (String, Boolean, String?) -> Unit,
    private val formatError: (String, Throwable) -> String,
    private val log: org.slf4j.Logger
) {
    fun open(symbol: String) {
        log.info(LogTag.UI, "open stock requested symbol={}", symbol)
        val isin = repository.loadInstrumentIsin(symbol)
        if (isin.isNullOrBlank()) {
            log.warn(LogTag.DB, "cannot open stock page because ISIN is unavailable symbol={}", symbol)
            setStatus("Cannot open stock page: no ISIN for $symbol", true, null)
            return
        }
        setStatus("Finding stock page: $symbol", false, null)
        CompletableFuture.supplyAsync { client.resolveStockUrl(searchUrl(), isin) }.whenComplete { url, error ->
            Platform.runLater {
                if (error == null && url != null) launch(symbol, url) else reportFailure(symbol, error)
            }
        }
    }

    private fun launch(symbol: String, url: String) {
        runCatching {
            log.info(LogTag.UI, "stock page resolved symbol={} url={}", symbol, url)
            openExternal(url)
        }.onSuccess {
            setStatus("Opened stock page: $symbol", false, null)
        }.onFailure { error ->
            reportFailure(symbol, error)
        }
    }

    private fun reportFailure(symbol: String, error: Throwable?) {
        log.warn(LogTag.API, "stock page lookup failed symbol={}", symbol, error)
        setStatus("Stock page not found: $symbol", true, error?.let { formatError(symbol, it) })
    }
}
