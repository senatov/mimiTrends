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

import org.senatov.mimitrends.model.ScanResult

internal object PublishedResultComposer {
    fun compose(
        current: Collection<ScanResult>,
        saved: Collection<ScanResult>,
        requestedLimit: Int,
        nowEpochSeconds: Long = java.time.Instant.now().epochSecond
    ): List<ScanResult> {
        val limit = requestedLimit.coerceAtLeast(1)
        val results = linkedMapOf<String, ScanResult>()
        current.forEach { result -> if (results.size < limit) results.putIfAbsent(result.symbol, result) }
        saved.asSequence()
            .filter { OpenMarketDataFreshness.isUsable(it.analysisUpdatedAtMillis / 1_000L, nowEpochSeconds) }
            .forEach { result -> if (results.size < limit) results.putIfAbsent(result.symbol, result) }
        return results.values.toList()
    }
}
