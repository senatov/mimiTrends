package org.senatov.mimitrends

internal object FeedFreshness {
    fun ageMinutes(updatedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): Long =
        (nowMillis - updatedAtMillis).coerceAtLeast(0L) / 60_000L

    fun ageLabel(updatedAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
        val elapsed = (nowMillis - updatedAtMillis).coerceAtLeast(0L)
        return if (elapsed < 60_000L) "<1m" else "${elapsed / 60_000L}m"
    }

    fun isStale(updatedAtMillis: Long, status: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        status != REFRESHING &&
        (nowMillis - updatedAtMillis).coerceAtLeast(0L) >
            (expectedDelayMinutes(status) + STALE_GRACE_MINUTES) * 60_000L

    fun icon(updatedAtMillis: Long, status: String, nowMillis: Long = System.currentTimeMillis()): String = when {
        status == REFRESHING -> "⌛"
        isStale(updatedAtMillis, status, nowMillis) -> "⚠"
        expectedDelayMinutes(status) > 0 -> "◷"
        else -> "●"
    }

    fun tooltip(updatedAtMillis: Long, status: String, nowMillis: Long = System.currentTimeMillis()): String {
        val age = ageLabel(updatedAtMillis, nowMillis)
        val expected = expectedDelayMinutes(status)
        return when {
            status == REFRESHING -> "Refreshing the selected signal and its latest market data"
            isStale(updatedAtMillis, status, nowMillis) ->
                "Stale market data · $age old · expected delay at most $expected minutes"
            expected > 0 -> "Delayed market data · $age old · expected delay $expected minutes"
            else -> "Current $status market data · $age old"
        }
    }

    private fun expectedDelayMinutes(status: String): Long =
        DELAY_MINUTES.find(status)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    private val DELAY_MINUTES = Regex("(\\d+)m", RegexOption.IGNORE_CASE)
    const val REFRESHING = "REFRESHING"
    private const val STALE_GRACE_MINUTES = 5L
}
