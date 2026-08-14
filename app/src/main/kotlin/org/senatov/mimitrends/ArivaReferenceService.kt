package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.ArivaInstrumentClient
import org.senatov.mimitrends.marketdata.ArivaInstrumentReference
import org.senatov.mimitrends.model.ProviderInstrument
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ArivaReferenceService(
    private val repository: MarketRepository,
    private val verify: (String) -> ArivaInstrumentReference = ArivaInstrumentClient()::verify
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-ariva-reference").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private var symbols = emptyList<String>()
    private var index = 0
    private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun replaceSymbols(values: Collection<String>) {
        if (closed.get()) return
        symbols = values.map(String::uppercase).distinct()
        if (index >= symbols.size) index = 0
        if (symbols.isNotEmpty() && task == null) scheduleNext(INITIAL_DELAY_MILLIS)
    }

    private fun pollNext() {
        var symbol: String? = null
        try {
            symbol = synchronized(this) {
                if (symbols.isEmpty()) return
                symbols[index].also { index = (index + 1) % symbols.size }
            }
            verifySymbol(requireNotNull(symbol))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            log.warn(LogTag.API, "ARIVA reference lookup failed symbol={} cause={}", symbol, error.toString())
        } finally {
            synchronized(this) {
                task = if (symbols.isEmpty()) null else scheduleNext(ArivaPollingSchedule.nextDelayMillis())
            }
        }
    }

    internal fun verifySymbol(symbol: String) {
        val companyName = repository.loadCompanyProfile(symbol)?.name
        val candidates = SOURCE_PROVIDERS.mapNotNull { repository.loadProviderInstrument(it, symbol) }
        val candidate = ProviderInstrumentSelector.select(
            symbol, companyName, candidates, repository.loadInstrumentIsin(symbol), ::isEquityIsin
        ) ?: return
        val reference = verify(candidate.identifier)
        repository.upsertProviderInstrument(ProviderInstrument(
            PROVIDER, symbol, reference.isin, MIC, candidate.currency, candidate.resolvedName
        ))
        log.info(LogTag.API, "ARIVA instrument verified symbol={} isin={} wkn={} page={}",
            symbol, reference.isin, reference.wkn, reference.pageUrl)
    }

    private fun scheduleNext(delayMillis: Long): ScheduledFuture<*> =
        scheduler.schedule(::pollNext, delayMillis, TimeUnit.MILLISECONDS)

    private fun isEquityIsin(instrument: ProviderInstrument): Boolean =
        ISIN.matches(instrument.identifier) && !instrument.identifier.startsWith("FRIX") &&
            !instrument.identifier.startsWith("XS")

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) { symbols = emptyList(); task?.cancel(false); task = null }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val PROVIDER = "ARIVA"
        const val MIC = "ARIVA"
        const val INITIAL_DELAY_MILLIS = 30_000L
        val SOURCE_PROVIDERS = listOf("EURONEXT", "TRADEGATE", "BOERSE_DE", "TRADERFOX", "BNP_PARIBAS")
        val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
    }
}

internal object ArivaPollingSchedule {
    fun nextDelayMillis(randomOffset: Long = ThreadLocalRandom.current().nextLong(-JITTER_MILLIS, JITTER_MILLIS + 1)) =
        BASE_INTERVAL_MILLIS + randomOffset.coerceIn(-JITTER_MILLIS, JITTER_MILLIS)

    const val BASE_INTERVAL_MILLIS = 30 * 60_000L
    const val JITTER_MILLIS = 5 * 60_000L
}
