package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult

internal class MarketObservationOverlay {
    private val latest = mutableMapOf<String, Observation>()

    fun record(symbol: String, price: Double, observedAtMillis: Long, source: String) {
        val current = latest[symbol]
        if (current == null || observedAtMillis > current.observedAtMillis) {
            latest[symbol] = Observation(price, observedAtMillis, source)
        }
    }

    fun apply(result: ScanResult): ScanResult {
        val observation = latest[result.symbol] ?: return result
        if (observation.observedAtMillis <= result.updatedAtMillis) return result
        return result.copy(
            price = observation.price,
            updatedAtMillis = observation.observedAtMillis,
            dataStatus = observation.source
        )
    }

    private data class Observation(val price: Double, val observedAtMillis: Long, val source: String)
}
