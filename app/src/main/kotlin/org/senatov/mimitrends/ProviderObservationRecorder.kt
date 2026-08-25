package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.VolumeStatus

/** Persists sampled executable prices as a distinct, volume-less provider series. */
internal class ProviderObservationRecorder(
    private val repository: MarketRepository,
    private val downstream: MarketObservationSink
) : MarketObservationSink {
    private val current = mutableMapOf<Pair<String, String>, ProviderMinuteBar>()

    @Synchronized
    override fun publish(observation: MarketPriceObservation) {
        record(observation)
        downstream.publish(observation)
    }

    private fun record(observation: MarketPriceObservation) {
        if (!observation.price.isFinite() || observation.price <= 0.0) return
        val provider = observation.provider.trim().uppercase()
        val symbol = observation.symbol.trim().uppercase()
        val instrument = repository.loadProviderInstrument(provider, symbol) ?: return
        val minute = observation.observedAtMillis / 60_000L * 60L
        val key = provider to symbol
        val previous = current[key]
        val bar = if (previous?.bar?.minuteEpochSeconds == minute) {
            previous.bar.copy(
                high = maxOf(previous.bar.high, observation.price),
                low = minOf(previous.bar.low, observation.price),
                close = observation.price
            )
        } else {
            MinuteBar(
                symbol, minute, observation.price, observation.price, observation.price,
                observation.price, 0.0, VolumeStatus.MISSING
            )
        }
        ProviderMinuteBar(
            provider, symbol, instrument.identifier, instrument.mic, instrument.currency,
            bar, observation.observedAtMillis
        ).also { value ->
            current[key] = value
            repository.upsertProviderMinuteBar(value)
        }
    }
}