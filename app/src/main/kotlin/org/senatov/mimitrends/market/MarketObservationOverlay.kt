package org.senatov.mimitrends.market

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
