package org.senatov.mimitrends.db

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class MinuteBarFlushScheduler(
    private val flush: () -> Unit,
    private val hasPending: () -> Boolean
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-sqlite-writer").apply { isDaemon = true }
    }
    private val lock = Any()
    private var task: ScheduledFuture<*>? = null
    private var closed = false

    fun request() = synchronized(lock) {
        if (!closed && task == null) task = executor.schedule(::run, 1, TimeUnit.SECONDS)
    }

    private fun run() {
        val active = synchronized(lock) {
            task = null
            !closed
        }
        if (!active) return
        flush()
        if (hasPending()) request()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            task?.cancel(false)
            task = null
        }
        executor.shutdownNow()
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
