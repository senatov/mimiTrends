package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ShortMoveRefreshCoordinator(
    private val loadMoves: (Collection<String>) -> List<ShortMove>,
    private val log: Logger,
    private val refreshIntervalMillis: Long = REFRESH_INTERVAL_MILLIS,
    private val publish: (List<ShortMove>) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-short-move-refresh").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private var task: ScheduledFuture<*>? = null
    private var periodicTask: ScheduledFuture<*>? = null
    private var refreshRequested = false
    @Volatile private var symbols = emptyList<String>()

    @Synchronized
    fun replaceSymbols(values: Collection<String>) {
        if (closed.get()) return
        val replacement = values.map(String::uppercase).distinct()
        symbols = replacement
        if (periodicTask == null) {
            periodicTask = executor.scheduleWithFixedDelay(
                ::request, refreshIntervalMillis, refreshIntervalMillis, TimeUnit.MILLISECONDS
            )
        }
        request()
    }

    @Synchronized
    fun request() {
        if (closed.get()) return
        refreshRequested = true
        if (task == null) task = executor.schedule(::refresh, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun refresh() {
        val requestedSymbols = synchronized(this) {
            task = null
            refreshRequested = false
            symbols
        }
        val moves = runCatching { if (requestedSymbols.isEmpty()) emptyList() else loadMoves(requestedSymbols) }
            .onFailure { log.warn(LogTag.DB, "short-move refresh failed symbols={}", requestedSymbols.size, it) }
            .getOrNull()
        if (moves != null && !closed.get() && requestedSymbols == symbols) publish(moves)
        synchronized(this) {
            if (!closed.get() && refreshRequested && task == null) {
                task = executor.schedule(::refresh, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) {
            task?.cancel(false)
            periodicTask?.cancel(false)
            task = null
            periodicTask = null
            refreshRequested = false
        }
        executor.shutdownNow()
        if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn(LogTag.DB, "short-move refresh executor did not stop within {}s", CLOSE_TIMEOUT_SECONDS)
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 350L
        const val REFRESH_INTERVAL_MILLIS = 180_000L
        const val CLOSE_TIMEOUT_SECONDS = 15L
    }
}

internal class ShortMoveEventRetainer {
    private val retainedBySymbol: MutableMap<String, RetainedMove> = LinkedHashMap()

    fun merge(current: Collection<ShortMove>, nowEpochSeconds: Long): List<ShortMove> {
        val expiredSymbols = retainedBySymbol
            .filterValues { retained -> nowEpochSeconds - retained.lastSeenEpochSeconds > RETENTION_SECONDS }
            .keys
        expiredSymbols.forEach(retainedBySymbol::remove)
        current.asSequence().filter(ShortMove::isActionableOpportunity).forEach { candidate ->
            retainedBySymbol[candidate.symbol] = RetainedMove(candidate, nowEpochSeconds)
        }
        val frozenSymbols = retainedBySymbol.keys
        return (retainedBySymbol.values.map(RetainedMove::move) + current.filter { it.symbol !in frozenSymbols })
            .distinctBy(ShortMove::symbol)
            .sortedByDescending(ShortMove::opportunityScore)
            .take(MAX_RETAINED_ROWS)
    }

    private data class RetainedMove(val move: ShortMove, val lastSeenEpochSeconds: Long)

    private companion object {
        const val RETENTION_SECONDS = 20 * 60L
        const val MAX_RETAINED_ROWS = 10
    }
}

private fun ShortMove.isActionableOpportunity(): Boolean =
    opportunityScore >= 0 && (pattern == ShortMovePattern.TRADABLE_CORRIDOR ||
            pattern == ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP)