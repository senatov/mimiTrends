package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScanResult
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class PriorityScanCoordinator(
    private val evaluate: (String) -> ScanResult?,
    private val onResult: (String, ScanResult?) -> Unit,
    private val intervalSeconds: Long = PRIORITY_SCAN_INTERVAL_SECONDS
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-priority-scanner").apply { isDaemon = true }
    }
    private val lock = Any()
    private val symbols = linkedSetOf<String>()
    private var task: ScheduledFuture<*>? = null
    private var generation = 0L

    fun replaceCandidates(results: Collection<ScanResult>) {
        synchronized(lock) {
            generation++
            symbols.clear()
            results.filter(::requiresPriorityScan).mapTo(symbols, ScanResult::symbol)
            if (symbols.isEmpty()) stopLocked() else ensureScheduledLocked()
        }
    }

    private fun ensureScheduledLocked() {
        if (task?.isDone == false) return
        log.info(LogTag.API, "priority scan started symbols={} interval={}s", symbols.size, intervalSeconds)
        task = scheduler.scheduleWithFixedDelay(::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
    }

    internal fun runOnce() {
        val (scanGeneration, snapshot) = synchronized(lock) { generation to symbols.toList() }
        snapshot.forEach { symbol ->
            runCatching {
                val result = evaluate(symbol)
                if (synchronized(lock) { generation == scanGeneration && symbol in symbols }) {
                    onResult(symbol, result)
                    if (result == null || !requiresPriorityScan(result)) remove(symbol)
                }
            }
                .onFailure { error ->
                    log.warn(LogTag.API, "priority scan failed symbol={}", symbol, error)
                }
        }
    }

    internal fun trackedSymbols(): Set<String> = synchronized(lock) { symbols.toSet() }

    private fun remove(symbol: String) {
        synchronized(lock) {
            symbols.remove(symbol)
            if (symbols.isEmpty()) stopLocked()
        }
    }

    private fun stopLocked() {
        if (task != null) log.info(LogTag.API, "priority scan stopped")
        task?.cancel(false)
        task = null
    }

    override fun close() {
        synchronized(lock) {
            generation++
            symbols.clear()
            stopLocked()
        }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(3, TimeUnit.SECONDS) }
    }

    internal companion object {
        const val STRONG_SCORE = 4.0
        const val PRIORITY_SCAN_INTERVAL_SECONDS = 60L

        fun requiresPriorityScan(result: ScanResult): Boolean =
            result.anomalyScore.isFinite() && result.anomalyScore >= STRONG_SCORE
    }
}
