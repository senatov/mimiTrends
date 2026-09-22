package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScanResult
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class PriorityScanCoordinator(
    private val evaluate: (String) -> ScanResult?,
    private val onResult: (String, ScanResult?) -> Unit,
    private val isUrgent: (String) -> Boolean = { false },
    private val intervalSeconds: Long = PRIORITY_SCAN_INTERVAL_SECONDS
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-priority-scanner").apply { isDaemon = true }
    }
    private val lock = Any()
    private val candidateSymbols = linkedSetOf<String>()
    private val urgentSymbols = linkedSetOf<String>()
    private var task: ScheduledFuture<*>? = null
    private var generation = 0L

    fun replaceCandidates(results: Collection<ScanResult>) {
        synchronized(lock) {
            generation++
            candidateSymbols.clear()
            results.filter(::requiresPriorityScan).mapTo(candidateSymbols, ScanResult::symbol)
            updateScheduleLocked()
        }
    }

    fun addUrgentSymbols(values: Collection<String>) {
        synchronized(lock) {
            generation++
            values.map(String::uppercase).mapTo(urgentSymbols) { it }
            updateScheduleLocked()
        }
    }

    fun clearUrgentSymbols() {
        synchronized(lock) {
            generation++
            urgentSymbols.clear()
            updateScheduleLocked()
        }
    }

    private fun updateScheduleLocked() {
        if (candidateSymbols.isEmpty() && urgentSymbols.isEmpty()) stopLocked() else ensureScheduledLocked()
    }

    private fun ensureScheduledLocked() {
        if (task?.isDone == false) return
        log.info(
            LogTag.API, "priority scan started symbols={} interval={}s",
            (candidateSymbols + urgentSymbols).size, intervalSeconds
        )
        task = scheduler.scheduleWithFixedDelay(::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
    }

    internal fun runOnce() {
        val (scanGeneration, snapshot) = synchronized(lock) { generation to (candidateSymbols + urgentSymbols).toList() }
        snapshot.forEach { symbol ->
            runCatching {
                val result = evaluate(symbol)
                if (synchronized(lock) {
                        generation == scanGeneration && (symbol in candidateSymbols || symbol in urgentSymbols)
                    }
                ) {
                    onResult(symbol, result)
                    val urgent = synchronized(lock) { symbol in urgentSymbols }
                    if (urgent && !isUrgent(symbol)) removeUrgent(symbol)
                    if (result == null || !requiresPriorityScan(result)) removeCandidate(symbol)
                }
            }
                .onFailure { error ->
                    log.warn(LogTag.API, "priority scan failed symbol={}", symbol, error)
                }
        }
    }

    internal fun trackedSymbols(): Set<String> = synchronized(lock) { (candidateSymbols + urgentSymbols).toSet() }

    private fun removeCandidate(symbol: String) {
        synchronized(lock) {
            candidateSymbols.remove(symbol)
            updateScheduleLocked()
        }
    }

    private fun removeUrgent(symbol: String) {
        synchronized(lock) {
            urgentSymbols.remove(symbol)
            updateScheduleLocked()
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
            candidateSymbols.clear()
            urgentSymbols.clear()
            stopLocked()
        }
        scheduler.shutdownNow()
        runCatching { scheduler.awaitTermination(20, TimeUnit.SECONDS) }
    }

    internal companion object {
        const val STRONG_SCORE = 4.0
        const val PRIORITY_SCAN_INTERVAL_SECONDS = 60L

        fun requiresPriorityScan(result: ScanResult): Boolean =
            result.anomalyScore.isFinite() && result.anomalyScore >= STRONG_SCORE
    }
}
