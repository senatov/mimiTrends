package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.LangSchwarzListing
import org.senatov.mimitrends.marketdata.LangSchwarzMarketDataClient
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderInstrument
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import org.senatov.mimitrends.model.VolumeStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class LangSchwarzPollingService(
    private val repository: MarketRepository,
    private val observationSink: MarketObservationSink,
    private val client: LangSchwarzMarketDataClient = LangSchwarzMarketDataClient()
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-lang-schwarz-provider").apply { isDaemon = true }
    }
    private var symbols = emptyList<String>()
    private var generation = 0L
    private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun replaceSymbols(values: Collection<String>) {
        symbols = values.map(String::uppercase).filter(ProviderBarTailMerger::isEuropeanSymbol).distinct()
        generation++
        task?.cancel(false)
        task = null
        if (symbols.isNotEmpty()) schedule(0L, generation)
        log.info(LogTag.API, "Lang & Schwarz provider configured symbols={}", symbols.size)
    }

    private fun poll(expectedGeneration: Long) {
        try {
            val targets = synchronized(this) {
                if (generation != expectedGeneration || symbols.isEmpty()) return
                symbols
            }
            val listings = client.loadEuropeanListings()
            targets.forEach { symbol -> match(symbol, listings)?.let { store(symbol, it) } }
        } catch (error: Exception) {
            if (error !is InterruptedException) {
                log.warn(LogTag.API, "Lang & Schwarz table crawl failed operation=poll", error)
            }
        } finally {
            synchronized(this) {
                if (generation == expectedGeneration && symbols.isNotEmpty()) schedule(POLL_INTERVAL_MILLIS, generation)
            }
        }
    }

    private fun match(symbol: String, listings: List<LangSchwarzListing>): LangSchwarzListing? {
        val profile = repository.loadCompanyProfile(symbol) ?: return null
        val identifiers = MATCHING_PROVIDERS.mapNotNull { repository.loadProviderInstrument(it, symbol)?.identifier }
        return LangSchwarzListingMatcher.match(symbol, profile.name, identifiers, listings)
    }

    private fun store(symbol: String, listing: LangSchwarzListing) {
        val now = System.currentTimeMillis()
        repository.upsertProviderInstrument(ProviderInstrument(
            PROVIDER, symbol, listing.itemId, MIC, CURRENCY, listing.name, now
        ))
        repository.upsertProviderQuote(ProviderQuoteSnapshot(
            PROVIDER, symbol, listing.itemId, CURRENCY, listing.midpoint, listing.bid, listing.ask,
            null, null, null, null, null, null, null, null, null, listing.observedAtMillis
        ))
        val minute = listing.observedAtMillis / 60_000L * 60L
        val bar = MinuteBar(symbol, minute, listing.midpoint, listing.midpoint, listing.midpoint,
            listing.midpoint, 0.0, VolumeStatus.MISSING)
        val observation = ProviderMinuteBar(
            PROVIDER, symbol, listing.itemId, MIC, CURRENCY, bar, listing.observedAtMillis
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
        const val PROVIDER = "LANG_SCHWARZ"
        const val MIC = "LSSI"
        const val CURRENCY = "EUR"
        const val POLL_INTERVAL_MILLIS = 30_000L
        val MATCHING_PROVIDERS = listOf("TRADEGATE", "EURONEXT", "BOERSE_DE", "BNP_PARIBAS")
    }
}
