package org.senatov.mimitrends

import kotlin.math.ceil

internal object ScanCyclePresentation {
    fun nextDelayMillis(intervalSeconds: Long, elapsedMillis: Long): Long =
        (intervalSeconds * 1_000L - elapsedMillis).coerceAtLeast(1_000L)

    fun countdownSeconds(delayMillis: Long): Long = ceil(delayMillis / 1_000.0).toLong()

    fun diagnostics(batch: ScannerBatchResult, elapsedMillis: Long): String {
        val duration = "%.1fs".format(elapsedMillis / 1_000.0)
        val oldest = batch.oldestDataAgeSeconds?.let(::age) ?: "unknown age"
        val sources = batch.sourceCoverage.entries
            .sortedByDescending(Map.Entry<String, Int>::value)
            .joinToString(", ") { "${it.key} ${it.value}" }
            .ifEmpty { "no source data" }
        return "$duration · oldest $oldest · $sources"
    }

    private fun age(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}
