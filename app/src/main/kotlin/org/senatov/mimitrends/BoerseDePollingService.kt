package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.BoerseDeMarketDataClient
import org.senatov.mimitrends.marketdata.BnpParibasMarketDataClient
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.senatov.mimitrends.model.VolumeStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class TableQuoteProviderGroup(
    private val repository: MarketRepository,
    observationSink: MarketObservationSink
) : AutoCloseable {
    private val boerseClient = BoerseDeMarketDataClient()
    private val bnpClient = BnpParibasMarketDataClient()
    private val langSchwarz = LangSchwarzPollingService(repository, observationSink)
    private val providers = listOf(
        IsinQuotePollingService(repository, observationSink, "BOERSE_DE", "XSTU") { isin ->
            boerseClient.loadQuote(isin).let { CrawledQuote(it.last, it.currency, it.observedAtMillis) }
        },
        IsinQuotePollingService(repository, observationSink, "BNP_PARIBAS", "BNPP") { isin ->
            bnpClient.loadQuote(isin).let { CrawledQuote(it.last, it.currency, it.observedAtMillis) }
        }
    )

    fun replaceSymbols(values: Collection<String>) {
        providers.forEach { it.replaceSymbols(values) }
        langSchwarz.replaceSymbols(values)
    }

    override fun close() {
        providers.forEach(AutoCloseable::close)
        langSchwarz.close()
    }
}

private data class CrawledQuote(val last: Double, val currency: String, val observedAtMillis: Long)

private class IsinQuotePollingService(
    private val repository: MarketRepository,
    private val observationSink: MarketObservationSink,
    private val provider: String,
    private val mic: String,
    private val loadQuote: (String) -> CrawledQuote
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-${provider.lowercase()}-provider").apply { isDaemon = true }
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
        log.info(LogTag.API, "table quote provider configured provider={} symbols={}", provider, symbols.size)
    }

    private fun pollNext(expectedGeneration: Long) {
        try {
            val symbol = synchronized(this) {
                if (symbols.isEmpty() || generation != expectedGeneration) return
                symbols[index].also { index = (index + 1) % symbols.size }
            }
            runCatching { poll(symbol) }.onFailure { error ->
                if (error !is InterruptedException) {
                    log.warn(LogTag.API, "table quote unavailable provider={} symbol={} cause={}",
                        provider, symbol, error.toString())
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
        val quote = loadQuote(instrument.identifier)
        repository.upsertProviderQuote(ProviderQuoteSnapshot(
            provider, symbol, instrument.identifier, quote.currency, quote.last, null, null,
            null, null, null, null, null, null, null, null, null, quote.observedAtMillis
        ))
        val minute = quote.observedAtMillis / 60_000L * 60L
        val bar = MinuteBar(symbol, minute, quote.last, quote.last, quote.last, quote.last, 0.0, VolumeStatus.MISSING)
        val observation = ProviderMinuteBar(
            provider, symbol, instrument.identifier, mic, quote.currency, bar, quote.observedAtMillis
        )
        if (repository.upsertProviderMinuteBar(observation)) observationSink.publish(observation)
    }

    private fun resolveInstrument(symbol: String): ProviderInstrument? {
        repository.loadProviderInstrument(provider, symbol)?.let { return it }
        val source = repository.loadProviderInstrument("TRADEGATE", symbol)
            ?: repository.loadProviderInstrument("EURONEXT", symbol)
            ?: return null
        return source.copy(provider = provider, mic = mic, updatedAtMillis = System.currentTimeMillis())
            .also(repository::upsertProviderInstrument)
    }

    override fun close() {
        synchronized(this) { generation++; task?.cancel(false); task = null; symbols = emptyList() }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val INTERVAL_MILLIS = 5_000L
    }
}
