package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

internal object PublishedResultComposer {
    fun compose(
        current: Collection<ScanResult>,
        saved: Collection<ScanResult>,
        requestedLimit: Int
    ): List<ScanResult> {
        val limit = requestedLimit.coerceAtLeast(1)
        val results = linkedMapOf<String, ScanResult>()
        current.forEach { result -> if (results.size < limit) results.putIfAbsent(result.symbol, result) }
        saved.forEach { result -> if (results.size < limit) results.putIfAbsent(result.symbol, result) }
        return results.values.toList()
    }
}
