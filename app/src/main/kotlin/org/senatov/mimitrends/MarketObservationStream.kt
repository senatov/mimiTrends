package org.senatov.mimitrends

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ScannerCriteria

internal interface MarketObservationSource : AutoCloseable {
    fun configure(criteria: ScannerCriteria)
}

internal fun interface MarketObservationSink {
    fun publish(observation: ProviderMinuteBar)
}

internal class MarketObservationBus : MarketObservationSink, AutoCloseable {
    private val channel = Channel<ProviderMinuteBar>(Channel.UNLIMITED)

    val observations: Flow<ProviderMinuteBar> = channel.receiveAsFlow()

    override fun publish(observation: ProviderMinuteBar) {
        check(channel.trySend(observation).isSuccess) { "Market observation bus is closed" }
    }

    override fun close() {
        channel.close()
    }
}
