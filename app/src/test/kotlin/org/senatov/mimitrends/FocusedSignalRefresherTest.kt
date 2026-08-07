package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FocusedSignalRefresherTest {
    @Test fun `publishes one focused refresh and observes cooldown`() {
        var evaluations = 0
        val completed = CountDownLatch(1)
        val loading = mutableListOf<Boolean>()
        FocusedSignalRefresher(
            evaluate = { evaluations++; TestScanResult.create(symbol = it) },
            onLoading = { _, value -> synchronized(loading) { loading += value } },
            onResult = { _, _ -> completed.countDown() },
            onError = { _, error -> throw error },
            cooldownMillis = 15_000L,
            nowMillis = { 20_000L }
        ).use { refresher ->
            refresher.request("test")
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            refresher.request("TEST")
        }

        assertEquals(1, evaluations)
        assertEquals(listOf(true, false), synchronized(loading) { loading.toList() })
    }
}
