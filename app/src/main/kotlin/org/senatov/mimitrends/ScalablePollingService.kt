package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.ScalableCliClient
import org.senatov.mimitrends.marketdata.ScalableCliUnavailableException
import org.senatov.mimitrends.marketdata.ScalableQuote
import org.senatov.mimitrends.marketdata.ScalableQuoteClient
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.senatov.mimitrends.model.VolumeStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class ScalablePollingService(
    private val repository: MarketRepository,
    private val observationSink: MarketObservationSink,
    private val fallback: (Collection<String>) -> Unit,
    private val client: ScalableQuoteClient = ScalableCliClient()
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
    }

    private fun poll(expectedGeneration: Long) {
        val targets = synchronized(this) {
            if (generation != expectedGeneration || symbols.isEmpty()) return
            symbols
        }
        try {
            client.verifyAccess()
            val unresolved = targets.filterNot(::pollSymbol)
            fallback(unresolved)
            log.debug(LogTag.API, "Scalable provider refreshed symbols={} fallback={}", targets.size, unresolved.size)
        } catch (error: ScalableCliUnavailableException) {
            fallback(targets)
            log.info(LogTag.API, "Scalable provider unavailable; using Lang & Schwarz fallback cause={}", error.message)
            return
        } catch (error: Exception) {
            fallback(targets)
            if (error !is InterruptedException) {
                log.warn(LogTag.API, "Scalable provider failed; using Lang & Schwarz fallback", error)
            }
            return
        }
        synchronized(this) {
            if (generation == expectedGeneration && symbols.isNotEmpty()) schedule(POLL_INTERVAL_MILLIS, generation)
        }
    }

    private fun pollSymbol(symbol: String): Boolean {
        val isin = repository.loadInstrumentIsin(symbol) ?: return false
        return try {
            store(symbol, client.loadQuote(isin))
            true
        } catch (error: ScalableCliUnavailableException) {
            log.debug(LogTag.API, "Scalable quote unavailable symbol={} cause={}", symbol, error.message)
            false
        }
    }

    private fun store(symbol: String, quote: ScalableQuote) {
        repository.upsertProviderInstrument(
            ProviderInstrument(
                PROVIDER, symbol, quote.isin, MIC, quote.currency, quote.name, quote.observedAtMillis
            )
        )
        repository.upsertProviderQuote(
            ProviderQuoteSnapshot(
                PROVIDER, symbol, quote.isin, quote.currency, quote.midpoint, quote.bid, quote.ask,
                null, null, null, null, null, null, null, null, quote.previousClose, quote.observedAtMillis
            )
        )
        val minute = quote.observedAtMillis / 60_000L * 60L
        val observation = ProviderMinuteBar(
            PROVIDER, symbol, quote.isin, MIC, quote.currency,
            MinuteBar(
                symbol, minute, quote.midpoint, quote.midpoint, quote.midpoint, quote.midpoint,
                0.0, VolumeStatus.MISSING
            ),
            quote.observedAtMillis
        )
        if (repository.upsertProviderMinuteBar(observation)) observationSink.publish(observation)
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
        const val POLL_INTERVAL_MILLIS = 30_000L
    }
}