package org.senatov.mimitrends

import org.senatov.mimitrends.marketdata.ProviderHttpException
import java.util.concurrent.ThreadLocalRandom

internal class ProviderBackoff {
    private var consecutiveFailures = 0
    private var blockedUntilMillis = 0L

    fun canRequest(nowMillis: Long = System.currentTimeMillis()): Boolean = nowMillis >= blockedUntilMillis

    fun success() {
        consecutiveFailures = 0
        blockedUntilMillis = 0L
    }

    fun failure(error: Throwable, nowMillis: Long = System.currentTimeMillis()): Long {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(MAX_FAILURES)
        val providerDelay = (error as? ProviderHttpException)?.retryAfterMillis
        val status = (error as? ProviderHttpException)?.statusCode
        val exponential = when (status) {
            403, 429, 503 -> BASE_BLOCK_MILLIS shl (consecutiveFailures - 1).coerceAtMost(6)
            else -> TRANSIENT_BLOCK_MILLIS shl (consecutiveFailures - 1).coerceAtMost(4)
        }
        val delay = maxOf(providerDelay ?: 0L, exponential).coerceAtMost(MAX_BLOCK_MILLIS)
        blockedUntilMillis = nowMillis + delay
        return delay
    }

    fun jitteredDelay(baseMillis: Long): Long {
        val spread = (baseMillis / 5).coerceAtLeast(1L)
        return ThreadLocalRandom.current().nextLong(
            (baseMillis - spread).coerceAtLeast(1L),
            baseMillis + spread + 1L
        )
    }

    private companion object {
        const val MAX_FAILURES = 10
        const val TRANSIENT_BLOCK_MILLIS = 5_000L
        const val BASE_BLOCK_MILLIS = 60_000L
        const val MAX_BLOCK_MILLIS = 6 * 60 * 60_000L
    }
}
