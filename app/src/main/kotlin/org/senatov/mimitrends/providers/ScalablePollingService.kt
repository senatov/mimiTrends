package org.senatov.mimitrends.providers

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
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.ScalableCliClient
import org.senatov.mimitrends.marketdata.ScalableCliUnavailableException
import org.senatov.mimitrends.marketdata.ScalableQuote
import org.senatov.mimitrends.marketdata.ScalableQuoteClient
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class ScalablePollingService(
    private val repository: MarketRepository,
    private val observationSink: MarketObservationSink,
    private val fallback: (Collection<String>) -> Unit,
    private val client: ScalableQuoteClient = ScalableCliClient(),
    private val activity: SourceActivity? = null,
    private val pollIntervalMillis: Long = 30_000L,
    private val unavailableRetryMillis: Long = 5 * 60_000L
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-scalable-provider").apply { isDaemon = true }
    }
    private var symbols = emptyList<String>()
    private var generation = 0L
    private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun replaceSymbols(values: Collection<String>) {
        symbols = values.map(String::uppercase).distinct().take(MAX_SYMBOLS)
        generation++
        task?.cancel(false)
        task = null
        fallback(emptyList())
        if (symbols.isNotEmpty()) schedule(0L, generation)
        else activity?.markStatus("Scalable", "No signals")
    }

    @Synchronized
    fun requestRefresh() {
        if (symbols.isEmpty()) return
        generation++
        task?.cancel(false)
        schedule(0L, generation)
    }

    private fun poll(expectedGeneration: Long) {
        val targets = synchronized(this) {
            if (generation != expectedGeneration || symbols.isEmpty()) return
            symbols
        }
        val startedNanos = System.nanoTime()
        var nextDelayMillis = pollIntervalMillis
        try {
            client.verifyAccess()
            var received = 0
            var accepted = 0
            val unresolved = targets.filter { symbol ->
                val result = pollSymbol(symbol)
                received += result.first
                if (result.second) accepted++
                !result.second
            }
            activity?.record("Scalable", received, accepted)
            fallback(unresolved)
            log.info(LogTag.API, "Scalable provider refreshed symbols={} received={} accepted={} fallback={} durationMs={}",
                targets.size, received, accepted, unresolved.size, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
        } catch (error: ScalableCliUnavailableException) {
            nextDelayMillis = unavailableRetryMillis
            activity?.record("Scalable", 0, 0, failed = true, status =
                if (error.message == "Scalable CLI login required") "Login needed" else "Unavailable")
            fallback(targets)
            log.info(LogTag.API, "Scalable provider unavailable; using Lang & Schwarz fallback cause={}", error.message)
        } catch (error: Exception) {
            nextDelayMillis = unavailableRetryMillis
            if (error !is InterruptedException) activity?.record("Scalable", 0, 0, failed = true)
            fallback(targets)
            if (error !is InterruptedException) {
                log.warn(LogTag.API, "Scalable provider failed; using Lang & Schwarz fallback", error)
            }
        } finally {
            synchronized(this) {
                if (generation == expectedGeneration && symbols.isNotEmpty()) schedule(nextDelayMillis, generation)
            }
        }
    }

    private fun pollSymbol(symbol: String): Pair<Int, Boolean> {
        val isin = repository.loadInstrumentIsin(symbol) ?: return 0 to false
        val quote = try {
            client.loadQuote(isin)
        } catch (error: ScalableCliUnavailableException) {
            log.debug(LogTag.API, "Scalable quote unavailable symbol={} cause={}", symbol, error.message)
            return 0 to false
        }
        return try {
            1 to store(symbol, quote)
        } catch (error: ScalableCliUnavailableException) {
            log.debug(LogTag.API, "Scalable quote rejected symbol={} cause={}", symbol, error.message)
            1 to false
        }
    }

    private fun store(symbol: String, quote: ScalableQuote): Boolean {
        val now = System.currentTimeMillis()
        if (quote.observedAtMillis !in (now - MAX_QUOTE_AGE_MILLIS)..(now + FUTURE_TOLERANCE_MILLIS)) {
            throw ScalableCliUnavailableException("Scalable quote is stale")
        }
        val expectedName = repository.loadCompanyProfile(symbol)?.name
        if (!ProviderInstrumentSelector.matchesCompany(symbol, expectedName, quote.name)) {
            log.warn(
                LogTag.API,
                "Scalable instrument rejected symbol={} expectedName={} resolvedName={}",
                symbol, expectedName, quote.name
            )
            throw ScalableCliUnavailableException("Scalable instrument identity does not match symbol")
        }
        repository.upsertProviderInstrument(
            ProviderInstrument(
                PROVIDER, symbol, quote.isin, MIC, quote.currency, quote.name, quote.observedAtMillis
            )
        )
        val stored = repository.upsertProviderQuote(
            ProviderQuoteSnapshot(
                PROVIDER, symbol, quote.isin, quote.currency, quote.midpoint, quote.bid, quote.ask,
                null, null, null, null, null, null, null, null, quote.previousClose, quote.observedAtMillis
            )
        )
        if (stored) {
            observationSink.publish(MarketPriceObservation(PROVIDER, symbol, quote.midpoint, quote.observedAtMillis))
        }
        return stored
    }

    private fun schedule(delayMillis: Long, expectedGeneration: Long) {
        task = scheduler.schedule({ poll(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        synchronized(this) { generation++; task?.cancel(false); task = null; symbols = emptyList() }
        fallback(emptyList())
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val PROVIDER = "SCALABLE"
        const val MIC = "SCALABLE"
        const val MAX_SYMBOLS = 30
        const val MAX_QUOTE_AGE_MILLIS = 2 * 60_000L
        const val FUTURE_TOLERANCE_MILLIS = 60_000L
    }
}
