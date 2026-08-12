package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ShortMoveRefreshCoordinator(
    private val loader: ShortMoveLoader,
    private val log: Logger,
    private val publish: (List<ShortMove>) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-short-move-refresh").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private var task: ScheduledFuture<*>? = null
    @Volatile private var symbols = emptyList<String>()

    fun replaceSymbols(values: Collection<String>) {
        symbols = values.map(String::uppercase).distinct()
    }

    @Synchronized
    fun request() {
        if (closed.get() || symbols.isEmpty()) return
        task?.cancel(false)
        task = executor.schedule(::refresh, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun refresh() {
        val requestedSymbols = symbols
        val moves = runCatching { loader.load(requestedSymbols) }
            .onFailure { log.warn(LogTag.DB, "short-move refresh failed symbols={}", requestedSymbols.size, it) }
            .getOrNull() ?: return
        if (!closed.get() && requestedSymbols == symbols) publish(moves)
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        task?.cancel(false)
        executor.shutdownNow()
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 350L
    }
}
