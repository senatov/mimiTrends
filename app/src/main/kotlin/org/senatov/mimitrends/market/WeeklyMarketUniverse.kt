package org.senatov.mimitrends.market

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.TraderFoxEquity
import org.senatov.mimitrends.marketdata.TraderFoxList
import org.senatov.mimitrends.marketdata.TraderFoxUniverseClient
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Properties
import java.util.concurrent.Executors

/** Refreshes a bounded Germany/US source universe at startup and keeps the last valid snapshot. */
internal class WeeklyMarketUniverse(
    private val load: (TraderFoxList) -> List<TraderFoxEquity> = TraderFoxUniverseClient()::load,
    private val path: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "weekly-universe.properties"),
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mimitrends-weekly-universe").apply { isDaemon = true }
    }
    @Volatile
    private var snapshot = readSnapshot()

    fun symbols(): List<String> = snapshot.symbols

    fun refreshAsync(onUpdated: () -> Unit) {
        executor.execute {
            runCatching { refreshIfDue() }
                .onSuccess { if (it) onUpdated() }
                .onFailure { log.warn(LogTag.API, "weekly market universe refresh failed; retaining cached symbols", it) }
        }
    }

    internal fun refreshIfDue(): Boolean {
        val now = nowMillis()
        if (snapshot.updatedAtMillis > 0 && now >= snapshot.updatedAtMillis &&
            now - snapshot.updatedAtMillis < REFRESH_INTERVAL_MILLIS
        ) return false

        val dax = load(TraderFoxList.DAX)
        val nyse = load(TraderFoxList.NYSE)
        val sp500 = load(TraderFoxList.SP_500)
        val nasdaq100 = load(TraderFoxList.NASDAQ_100)
        require(dax.size in 35..45 && nyse.size >= 500 && sp500.size >= 400 && nasdaq100.size >= 80) {
            "TraderFox universe response is incomplete"
        }
        val selected = select(dax, nyse, sp500, nasdaq100, now)
        require(selected.size >= 100 && selected.count { it.endsWith(".DE") } >= 35) {
            "TraderFox universe has too few current equities"
        }
        val next = Snapshot(now, selected)
        writeSnapshot(next)
        snapshot = next
        log.info(
            LogTag.API, "weekly market universe updated dax={} us={}",
            selected.count { it.endsWith(".DE") }, selected.count { !it.endsWith(".DE") })
        return true
    }

    internal fun select(
        dax: List<TraderFoxEquity>, nyse: List<TraderFoxEquity>,
        sp500: List<TraderFoxEquity>, nasdaq100: List<TraderFoxEquity>, now: Long
    ): List<String> {
        val current = { equity: TraderFoxEquity, currency: String ->
            equity.currency == currency && equity.price >= 2.0 &&
                    now / 1_000 - equity.quoteEpochSeconds in 0..MAX_QUOTE_AGE_SECONDS
        }
        val german = dax.filter { current(it, "EUR") }.map { "${it.symbol}.DE" }
        val nyseSymbols = nyse.filter { current(it, "USD") }.mapTo(hashSetOf(), TraderFoxEquity::symbol)
        val sp500Nyse = sp500.filter { current(it, "USD") && it.symbol in nyseSymbols }
            .sortedWith(ACTIVITY_ORDER).take(MAX_NYSE)
        val nasdaq = nasdaq100.filter { current(it, "USD") && it.symbol !in nyseSymbols }
            .sortedWith(ACTIVITY_ORDER).take(MAX_NASDAQ)
        return (german + sp500Nyse.map(TraderFoxEquity::symbol) +
                nasdaq.map(TraderFoxEquity::symbol)).distinct()
    }

    private fun readSnapshot(): Snapshot = runCatching {
        if (!Files.exists(path)) return Snapshot(0, emptyList())
        val properties = Properties().also { Files.newInputStream(path).use(it::load) }
        val updated = properties.getProperty("updatedAtMillis")?.toLongOrNull() ?: 0
        val symbols = properties.getProperty("symbols").orEmpty().split(',')
            .filter { SYMBOL.matches(it) }.distinct().take(MAX_SNAPSHOT_SYMBOLS)
        Snapshot(updated.takeIf { symbols.size >= MIN_VALID_SNAPSHOT_SYMBOLS } ?: 0, symbols)
    }.getOrElse { error ->
        log.warn(LogTag.IO, "weekly market universe cache read failed", error)
        Snapshot(0, emptyList())
    }

    private fun writeSnapshot(value: Snapshot) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, "weekly-universe-", ".tmp")
        try {
            val properties = Properties().apply {
                setProperty("updatedAtMillis", value.updatedAtMillis.toString())
                setProperty("symbols", value.symbols.joinToString(","))
            }
            Files.newOutputStream(temporary).use { properties.store(it, "MiMiTrends weekly market universe") }
            runCatching { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                .getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private data class Snapshot(val updatedAtMillis: Long, val symbols: List<String>)

    private companion object {
        const val MAX_NYSE = 80
        const val MAX_NASDAQ = 40
        const val MAX_SNAPSHOT_SYMBOLS = 165
        const val MIN_VALID_SNAPSHOT_SYMBOLS = 100
        val MAX_QUOTE_AGE_SECONDS = Duration.ofDays(7).seconds
        val REFRESH_INTERVAL_MILLIS = Duration.ofDays(7).toMillis()
        val SYMBOL = Regex("[A-Z0-9][A-Z0-9-]{0,9}(\\.DE)?")
        val ACTIVITY_ORDER = compareByDescending<TraderFoxEquity> { kotlin.math.abs(it.changePercent) }
            .thenByDescending(TraderFoxEquity::quoteEpochSeconds)
            .thenBy(TraderFoxEquity::symbol)
    }
}