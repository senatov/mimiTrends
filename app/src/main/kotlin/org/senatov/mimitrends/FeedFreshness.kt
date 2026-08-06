package org.senatov.mimitrends

import kotlin.math.ceil

internal object FeedFreshness {
    fun ageMinutes(updatedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long =
        ceil((nowMillis - updatedAtMillis).coerceAtLeast(0L) / 60_000.0).toLong()

    fun isStale(updatedAtMillis: Long, status: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        ageMinutes(updatedAtMillis, nowMillis) > expectedDelayMinutes(status) + STALE_GRACE_MINUTES

    fun icon(updatedAtMillis: Long, status: String, nowMillis: Long = System.currentTimeMillis()): String = when {
        isStale(updatedAtMillis, status, nowMillis) -> "⚠"
        expectedDelayMinutes(status) > 0 -> "◷"
        else -> "●"
    }

    fun tooltip(updatedAtMillis: Long, status: String, nowMillis: Long = System.currentTimeMillis()): String {
        val age = ageMinutes(updatedAtMillis, nowMillis)
        val expected = expectedDelayMinutes(status)
        return when {
            isStale(updatedAtMillis, status, nowMillis) ->
                "Stale market data · $age minutes old · expected delay at most $expected minutes"
            expected > 0 -> "Delayed market data · $age minutes old · expected delay $expected minutes"
            else -> "Current market data · $age minutes old"
        }
    }

    private fun expectedDelayMinutes(status: String): Long =
        DELAY_MINUTES.find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private val DELAY_MINUTES = Regex("(\\d+)m", RegexOption.IGNORE_CASE)
    private const val STALE_GRACE_MINUTES = 5L
}
