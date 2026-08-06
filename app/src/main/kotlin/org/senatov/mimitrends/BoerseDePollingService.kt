package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.BoerseDeMarketDataClient
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.senatov.mimitrends.model.VolumeStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class BoerseDePollingService(
    private val repository: MarketRepository,
    private val observationSink: MarketObservationSink,
    private val client: BoerseDeMarketDataClient = BoerseDeMarketDataClient()
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-boerse-de-provider").apply { isDaemon = true }
    }
    private var symbols = emptyList<String>()
    private var index = 0
    private var generation = 0L
    private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun replaceSymbols(values: Collection<String>) {
        symbols = values.map(String::uppercase).filter(ProviderBarTailMerger::isEuropeanSymbol).distinct()
        index = 0
        generation++
        task?.cancel(false)
        task = null
        if (symbols.isNotEmpty()) scheduleNext(0L, generation)
        log.info(LogTag.API, "boerse.de provider configured symbols={}", symbols.size)
    }

    private fun pollNext(expectedGeneration: Long) {
        try {
            val symbol = synchronized(this) {
                if (symbols.isEmpty() || generation != expectedGeneration) return
                symbols[index].also { index = (index + 1) % symbols.size }
            }
            runCatching { poll(symbol) }.onFailure { error ->
                if (error !is InterruptedException) {
                    log.warn(LogTag.API, "boerse.de quote unavailable symbol={} cause={}", symbol, error.toString())
                }
            }
        } finally {
            synchronized(this) {
                if (symbols.isNotEmpty() && generation == expectedGeneration) scheduleNext(INTERVAL_MILLIS, expectedGeneration)
            }
        }
    }

    private fun scheduleNext(delayMillis: Long, expectedGeneration: Long) {
        task = scheduler.schedule({ pollNext(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun poll(symbol: String) {
        val instrument = resolveInstrument(symbol) ?: return
        val quote = client.loadQuote(instrument.identifier)
        repository.upsertProviderQuote(ProviderQuoteSnapshot(
            PROVIDER, symbol, instrument.identifier, quote.currency, quote.last, null, null,
            null, null, null, null, null, null, null, null, null, quote.observedAtMillis
        ))
        val minute = quote.observedAtMillis / 60_000L * 60L
        val bar = MinuteBar(symbol, minute, quote.last, quote.last, quote.last, quote.last, 0.0, VolumeStatus.MISSING)
        val observation = ProviderMinuteBar(
            PROVIDER, symbol, instrument.identifier, MIC, quote.currency, bar, quote.observedAtMillis
        )
        if (repository.upsertProviderMinuteBar(observation)) observationSink.publish(observation)
    }

    private fun resolveInstrument(symbol: String): ProviderInstrument? {
        repository.loadProviderInstrument(PROVIDER, symbol)?.let { return it }
        val source = repository.loadProviderInstrument("TRADEGATE", symbol)
            ?: repository.loadProviderInstrument("EURONEXT", symbol)
            ?: return null
        return source.copy(provider = PROVIDER, mic = MIC, updatedAtMillis = System.currentTimeMillis())
            .also(repository::upsertProviderInstrument)
    }

    override fun close() {
        synchronized(this) { generation++; task?.cancel(false); task = null; symbols = emptyList() }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val PROVIDER = "BOERSE_DE"
        const val MIC = "XSTU"
        const val INTERVAL_MILLIS = 5_000L
    }
}
