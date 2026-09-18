package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

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
        val reused = if (batch.reusedAnalyses > 0) " · reused ${batch.reusedAnalyses}" else ""
        return "$duration · oldest $oldest · $sources$reused"
    }

    private fun age(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}
