package org.senatov.mimitrends.services

import org.senatov.mimitrends.marketdata.ScalableCliClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal enum class ScalableLoginEvent {
    OPENED,
    READY,
    TIMED_OUT,
    FAILED
}

/** Watches the CLI-owned session after the user completes login in Terminal and the browser. */
internal class ScalableLoginCoordinator(
    private val launch: () -> Unit = ScalableTerminalLoginLauncher::launch,
    private val verify: () -> Unit = ScalableCliClient()::verifyAccess,
    private val pollMillis: Long = 5_000L,
    private val timeoutMillis: Long = 10 * 60_000L
) : AutoCloseable {
    private val running = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mimitrends-scalable-login-check").apply { isDaemon = true }
    }

    fun start(onEvent: (ScalableLoginEvent) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        executor.execute {
            try {
                launch()
                onEvent(ScalableLoginEvent.OPENED)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                while (!Thread.currentThread().isInterrupted && System.nanoTime() < deadline) {
                    Thread.sleep(pollMillis)
                    val accessible = try {
                        verify()
                        true
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    } catch (_: Exception) {
                        false
                    }
                    if (accessible) {
                        onEvent(ScalableLoginEvent.READY)
                        return@execute
                    }
                }
                if (!Thread.currentThread().isInterrupted) onEvent(ScalableLoginEvent.TIMED_OUT)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                onEvent(ScalableLoginEvent.FAILED)
            } finally {
                running.set(false)
            }
        }
        return true
    }

    override fun close() {
        executor.shutdownNow()
    }
}