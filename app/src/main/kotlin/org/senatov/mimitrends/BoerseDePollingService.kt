package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.BoerseDeMarketDataClient
import org.senatov.mimitrends.marketdata.BnpParibasMarketDataClient
import org.senatov.mimitrends.marketdata.TraderFoxMarketDataClient
import org.senatov.mimitrends.marketdata.ProviderDataUnavailableException
import org.senatov.mimitrends.marketdata.ProviderHttpException
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
    private val traderFoxClient = TraderFoxMarketDataClient()
    private val langSchwarz = LangSchwarzPollingService(repository, observationSink)
    private val providers = listOf(
        IsinQuotePollingService(repository, observationSink, "BOERSE_DE", "XSTU") { isin ->
            boerseClient.loadQuote(isin).let { CrawledQuote(it.last, it.currency, it.observedAtMillis) }
        },
        IsinQuotePollingService(repository, observationSink, "BNP_PARIBAS", "BNPP") { isin ->
            bnpClient.loadQuote(isin).let { CrawledQuote(it.last, it.currency, it.observedAtMillis) }
        },
        IsinQuotePollingService(repository, observationSink, "TRADERFOX", "TFX") { isin ->
            traderFoxClient.loadQuote(isin).let { CrawledQuote(it.last, it.currency, it.observedAtMillis) }
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
    private val unavailableUntil = mutableMapOf<String, Long>()
    private val backoff = ProviderBackoff()

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
            if ((unavailableUntil[symbol] ?: 0L) > System.currentTimeMillis() || !backoff.canRequest()) return
            runCatching { poll(symbol) }
                .onSuccess { backoff.success() }
                .onFailure { error -> handleFailure(symbol, error) }
        } finally {
            synchronized(this) {
                if (symbols.isNotEmpty() && generation == expectedGeneration) {
                    scheduleNext(backoff.jitteredDelay(INTERVAL_MILLIS), expectedGeneration)
                }
            }
        }
    }

    private fun handleFailure(symbol: String, error: Throwable) {
        when {
            error is InterruptedException -> return
            error is ProviderDataUnavailableException -> {
                backoff.success()
                log.debug(LogTag.API, "table quote has no current price provider={} symbol={} cause={}",
                    provider, symbol, error.message)
            }
            error is ProviderHttpException && error.statusCode in PERMANENT_INSTRUMENT_STATUSES -> {
                repository.deleteProviderInstrument(provider, symbol)
                unavailableUntil[symbol] = System.currentTimeMillis() + UNAVAILABLE_RETRY_MILLIS
                backoff.success()
                log.warn(LogTag.API, "table quote instrument disabled provider={} symbol={} status={} retryIn={}h",
                    provider, symbol, error.statusCode, TimeUnit.MILLISECONDS.toHours(UNAVAILABLE_RETRY_MILLIS))
            }
            else -> {
                val delay = backoff.failure(error)
                log.warn(LogTag.API, "table quote provider paused provider={} symbol={} delay={}ms cause={}",
                    provider, symbol, delay, error.toString())
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
        val companyName = repository.loadCompanyProfile(symbol)?.name
        repository.loadProviderInstrument(provider, symbol)?.let { cached ->
            if (isEquityIdentifier(cached.identifier) &&
                ProviderInstrumentSelector.matchesCompany(symbol, companyName, cached.resolvedName)) return cached
            repository.deleteProviderInstrument(provider, symbol)
            log.info(LogTag.API, "discarded mismatched table quote instrument provider={} symbol={} name={}",
                provider, symbol, cached.resolvedName)
        }
        val candidates = SOURCE_PROVIDERS.asSequence().filter { it != provider }
            .mapNotNull { repository.loadProviderInstrument(it, symbol) }.toList()
        val source = ProviderInstrumentSelector.select(symbol, companyName, candidates) {
            isEquityIdentifier(it.identifier)
        }
            ?: return null
        return source.copy(provider = provider, mic = mic, updatedAtMillis = System.currentTimeMillis())
            .also(repository::upsertProviderInstrument)
    }

    private fun isEquityIdentifier(identifier: String): Boolean =
        ISIN.matches(identifier) && !identifier.startsWith("FRIX") && !identifier.startsWith("XS")

    override fun close() {
        synchronized(this) { generation++; task?.cancel(false); task = null; symbols = emptyList() }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val INTERVAL_MILLIS = 5_000L
        const val UNAVAILABLE_RETRY_MILLIS = 24 * 60 * 60_000L
        val PERMANENT_INSTRUMENT_STATUSES = setOf(400, 404)
        val SOURCE_PROVIDERS = listOf("TRADEGATE", "EURONEXT", "BOERSE_DE", "BNP_PARIBAS", "TRADERFOX")
        val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
    }
}
