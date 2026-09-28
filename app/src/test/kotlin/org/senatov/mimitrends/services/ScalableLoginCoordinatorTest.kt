package org.senatov.mimitrends.services

import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScalableLoginCoordinatorTest {
    @Test
    fun `detects a session after interactive login is opened`() {
        val checks = AtomicInteger()
        val ready = CountDownLatch(1)
        val events = mutableListOf<ScalableLoginEvent>()
        ScalableLoginCoordinator({}, {
            if (checks.incrementAndGet() < 2) error("no session")
        }, pollMillis = 10, timeoutMillis = 1_000).use { coordinator ->
            assertTrue(coordinator.start { event ->
                synchronized(events) { events += event }
                if (event == ScalableLoginEvent.READY) ready.countDown()
            })
            assertFalse(coordinator.start {})
            assertTrue(ready.await(2, TimeUnit.SECONDS))
        }
        assertEquals(listOf(ScalableLoginEvent.OPENED, ScalableLoginEvent.READY), events)
    }

    @Test
    fun `terminal script invokes local read only login without credentials`() {
        val script = ScalableTerminalLoginLauncher.scriptFor(Path.of("/tmp/O'Brien/sc"))
        assertTrue(script.contains("'/tmp/O'\"'\"'Brien/sc' login --local-read-only"))
        assertTrue(script.contains("open \"\${activate_url}\""))
        assertTrue(script.contains("tee \"\${log_file}\""))
        assertFalse(script.contains("password", ignoreCase = true))
    }
}
