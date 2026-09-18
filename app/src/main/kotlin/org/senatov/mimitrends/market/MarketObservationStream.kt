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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.senatov.mimitrends.model.ScannerCriteria

internal data class MarketPriceObservation(
    val provider: String,
    val symbol: String,
    val price: Double,
    val observedAtMillis: Long
)

internal interface MarketObservationSource : AutoCloseable {
    fun configure(criteria: ScannerCriteria)
}

internal fun interface MarketObservationSink {
    fun publish(observation: MarketPriceObservation)
}

internal class MarketObservationBus : MarketObservationSink, AutoCloseable {
    private val channel = Channel<MarketPriceObservation>(Channel.UNLIMITED)

    val observations: Flow<MarketPriceObservation> = channel.receiveAsFlow()

    override fun publish(observation: MarketPriceObservation) {
        check(channel.trySend(observation).isSuccess) { "Market observation bus is closed" }
    }

    override fun close() {
        channel.close()
    }
}
