package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.EuronextInstrument
import org.senatov.mimitrends.marketdata.EuronextMarketDataClient
import org.senatov.mimitrends.marketdata.ProviderDataUnavailableException
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.VolumeStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

internal class EuronextPollingService(
    private val repository: MarketRepository,
    private val client: EuronextMarketDataClient = EuronextMarketDataClient(),
    private val observationSink: MarketObservationSink = MarketObservationSink {}
) : MarketObservationSource {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-euronext-provider").apply { isDaemon = true }
    }
    private val unresolvedUntil = mutableMapOf<String, Long>()
    private val backoff = ProviderBackoff()
    private var symbols = emptyList<String>()
    private var index = 0
    private var intervalMillis = 1_500L
    private var generation = 0L
    private var task: ScheduledFuture<*>? = null

    @Synchronized
    override fun configure(criteria: ScannerCriteria) {
        task?.cancel(false)
        task = null
        generation++
        backoff.success()
        symbols = criteria.symbols.map(String::uppercase).filter(ProviderBarTailMerger::isEuropeanSymbol).distinct()
        index = 0
        if (!criteria.euronextEnabled || symbols.isEmpty()) {
            log.info(LogTag.API, "Euronext provider disabled")
            return
        }
        intervalMillis = criteria.euronextRequestIntervalMillis.coerceIn(750, 15_000)
        scheduleNext(0, generation)
        log.info(LogTag.API, "Euronext provider started symbols={} interval={}ms", symbols.size, intervalMillis)
    }

    private fun pollNextSafely(expectedGeneration: Long) {
        try {
            if (!isTradingSession(Instant.now()) || !backoff.canRequest()) return
            val symbol = synchronized(this) {
                if (symbols.isEmpty() || generation != expectedGeneration) return
                symbols[index].also { index = (index + 1) % symbols.size }
            }
            runCatching { poll(symbol) }
                .onSuccess { backoff.success() }
                .onFailure { error ->
                    if (error is InterruptedException) return@onFailure
                    if (error is ProviderDataUnavailableException) {
                        backoff.success()
                        log.debug(LogTag.API, "Euronext quote unavailable symbol={} cause={}", symbol, error.message)
                        return@onFailure
                    }
                    val delay = backoff.failure(error)
                    log.warn(LogTag.API, "Euronext request paused symbol={} delay={}ms cause={}", symbol, delay, error.toString())
                }
        } finally {
            synchronized(this) {
                if (generation == expectedGeneration && symbols.isNotEmpty()) {
                    scheduleNext(backoff.jitteredDelay(intervalMillis), expectedGeneration)
                }
            }
        }
    }

    private fun scheduleNext(delayMillis: Long, expectedGeneration: Long) {
        task = scheduler.schedule({ pollNextSafely(expectedGeneration) }, delayMillis, TimeUnit.MILLISECONDS)
    }

    internal fun isTradingSession(instant: Instant): Boolean {
        val local = instant.atZone(EURONEXT_ZONE)
        return local.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
            !local.toLocalTime().isBefore(OPEN) && local.toLocalTime().isBefore(CLOSE)
    }

    private fun poll(symbol: String) {
        val instrument = resolve(symbol) ?: return
        val source = EuronextInstrument(instrument.identifier, instrument.mic, instrument.resolvedName)
        val quote = client.loadQuote(source)
        repository.upsertProviderQuote(ProviderQuoteSnapshot(
            PROVIDER, symbol, instrument.identifier, quote.currency, quote.last, quote.bid, quote.ask,
            null, null, null, null, null, null, null, null, null, quote.observedAtMillis
        ))
        val minute = quote.observedAtMillis / 60_000L * 60L
        val bar = MinuteBar(symbol, minute, quote.last, quote.last, quote.last, quote.last, 0.0, VolumeStatus.MISSING)
        val observation = ProviderMinuteBar(
            PROVIDER, symbol, instrument.identifier, instrument.mic, quote.currency, bar, quote.observedAtMillis
        )
        val stored = repository.upsertProviderMinuteBar(observation)
        if (stored) {
            observationSink.publish(observation)
            log.debug(LogTag.DB, "Euronext quote stored symbol={} isin={} mic={} minute={} price={}",
                symbol, instrument.identifier, instrument.mic, minute, quote.last)
        }
    }

    private fun resolve(symbol: String): ProviderInstrument? {
        val expectedIsin = repository.loadInstrumentIsin(symbol)
        repository.loadProviderInstrument(PROVIDER, symbol)?.let { cached ->
            if (!cached.identifier.startsWith(INDEX_ISIN_PREFIX) &&
                ProviderInstrumentSelector.matchesIdentity(expectedIsin, cached)) return cached
            repository.deleteProviderInstrument(PROVIDER, symbol)
            log.info(LogTag.API, "discarded non-equity Euronext instrument symbol={} identifier={}",
                symbol, cached.identifier)
        }
        val now = System.currentTimeMillis()
        if ((unresolvedUntil[symbol] ?: 0L) > now) return null
        val query = repository.loadCompanyProfile(symbol)?.name
            ?.let { CompanySearchTerm.from(it, symbol) }
            ?: symbol.substringBefore('.')
        val resolved = client.resolveInstrument(query)
        if (resolved == null) {
            unresolvedUntil[symbol] = now + UNRESOLVED_RETRY_MILLIS
            return null
        }
        return ProviderInstrument(PROVIDER, symbol, resolved.isin, resolved.mic, "EUR", resolved.name, now)
            .also(repository::upsertProviderInstrument)
    }

    override fun close() {
        synchronized(this) { generation++; task?.cancel(false); task = null; symbols = emptyList() }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val PROVIDER = "EURONEXT"
        const val INDEX_ISIN_PREFIX = "FRIX"
        const val UNRESOLVED_RETRY_MILLIS = 24 * 60 * 60_000L
        val EURONEXT_ZONE: ZoneId = ZoneId.of("Europe/Paris")
        val OPEN: LocalTime = LocalTime.of(7, 0)
        val CLOSE: LocalTime = LocalTime.of(22, 0)
    }
}
