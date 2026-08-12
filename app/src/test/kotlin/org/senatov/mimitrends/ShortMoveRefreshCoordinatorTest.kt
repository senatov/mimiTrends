package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortMoveRefreshCoordinatorTest {
    @Test
    fun `continuous requests cannot postpone refresh indefinitely`() {
        val refreshes = AtomicInteger()
        val firstRefresh = CountDownLatch(1)
        ShortMoveRefreshCoordinator(
            loadMoves = {
                refreshes.incrementAndGet()
                emptyList()
            },
            log = LoggerFactory.getLogger(javaClass),
            publish = { firstRefresh.countDown() }
        ).use { coordinator ->
            coordinator.replaceSymbols(listOf("AAPL"))
            val requests = thread {
                repeat(12) {
                    Thread.sleep(75)
                    coordinator.request()
                }
            }

            assertTrue(firstRefresh.await(700, TimeUnit.MILLISECONDS))
            requests.join()
            assertTrue(refreshes.get() >= 1)
        }
    }

    @Test
    fun `replacing an unchanged symbol set still refreshes data freshness`() {
        val publications = mutableListOf<List<ShortMove>>()
        val published = CountDownLatch(2)
        val loads = AtomicInteger()
        ShortMoveRefreshCoordinator(
            loadMoves = { symbols ->
                if (loads.getAndIncrement() == 0) listOf(move(symbols.single())) else emptyList()
            },
            log = LoggerFactory.getLogger(javaClass),
            publish = {
                synchronized(publications) { publications += it }
                published.countDown()
            }
        ).use { coordinator ->
            coordinator.replaceSymbols(listOf("aapl"))
            assertTrue(waitUntil { synchronized(publications) { publications.size == 1 } })

            coordinator.replaceSymbols(listOf("AAPL"))

            assertTrue(published.await(1, TimeUnit.SECONDS))
            assertEquals(listOf("AAPL"), publications.first().map(ShortMove::symbol))
            assertTrue(publications.last().isEmpty())
        }
    }

    @Test
    fun `close waits for an active refresh to finish`() {
        val loadStarted = CountDownLatch(1)
        val allowLoadToFinish = CountDownLatch(1)
        val coordinator = ShortMoveRefreshCoordinator(
            loadMoves = {
                loadStarted.countDown()
                while (allowLoadToFinish.count > 0) {
                    try {
                        allowLoadToFinish.await()
                    } catch (_: InterruptedException) {
                        // Simulate a database call that cannot be cancelled by thread interruption.
                    }
                }
                emptyList()
            },
            log = LoggerFactory.getLogger(javaClass),
            publish = {}
        )
        coordinator.replaceSymbols(listOf("AAPL"))
        assertTrue(loadStarted.await(1, TimeUnit.SECONDS))

        val closeFinished = CountDownLatch(1)
        val closing = thread {
            coordinator.close()
            closeFinished.countDown()
        }
        assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))

        allowLoadToFinish.countDown()
        assertTrue(closeFinished.await(1, TimeUnit.SECONDS))
        closing.join()
    }

    @Test
    fun `periodic refresh runs independently of scanner requests`() {
        val publications = CountDownLatch(2)
        ShortMoveRefreshCoordinator(
            loadMoves = { emptyList() },
            log = LoggerFactory.getLogger(javaClass),
            publish = { publications.countDown() },
            refreshIntervalMillis = 100L
        ).use { coordinator ->
            coordinator.replaceSymbols(listOf("AAPL"))

            assertTrue(publications.await(2, TimeUnit.SECONDS))
        }
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    private fun move(symbol: String) = ShortMove(symbol, 1.0, 10.0, 10.1, 0L, 60L, 2)
}
