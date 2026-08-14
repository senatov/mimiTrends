package org.senatov.mimitrends

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class FocusedSignalRefresher(
    private val evaluate: (String) -> FocusedSignalRefresh,
    private val onLoading: (String, Boolean) -> Unit,
    private val onResult: (String, FocusedSignalRefresh) -> Unit,
    private val onError: (String, Throwable) -> Unit,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mimitrends-focused-signal-refresh").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    private val lastCompleted = ConcurrentHashMap<String, Long>()

    fun request(symbol: String) {
        val normalized = symbol.uppercase()
        val now = nowMillis()
        val completedAt = lastCompleted[normalized]
        if (completedAt != null && now - completedAt < cooldownMillis) return
        val requestGeneration = generation.incrementAndGet()
        onLoading(normalized, true)
        executor.execute {
            try {
                val result = evaluate(normalized)
                lastCompleted[normalized] = nowMillis()
                if (generation.get() == requestGeneration) onResult(normalized, result)
            } catch (error: Throwable) {
                if (generation.get() == requestGeneration) onError(normalized, error)
            } finally {
                if (generation.get() == requestGeneration) onLoading(normalized, false)
            }
        }
    }

    override fun close() {
        generation.incrementAndGet()
        executor.shutdownNow()
        runCatching { executor.awaitTermination(20, TimeUnit.SECONDS) }
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 15_000L
    }
}
