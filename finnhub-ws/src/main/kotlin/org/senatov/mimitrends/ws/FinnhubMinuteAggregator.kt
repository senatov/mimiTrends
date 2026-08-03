package org.senatov.mimitrends.ws

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.TradeTick
import org.senatov.mimitrends.model.VolumeStatus
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/** Builds one-minute OHLCV bars and publishes only a completed minute. */
class FinnhubMinuteAggregator(private val onBar: Consumer<MinuteBar>) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val bars = ConcurrentHashMap<String, MinuteBar>()

    fun accept(tick: TradeTick) {
        log.trace(LogTag.API, "accept(symbol={}, timestamp={})", tick.symbol, tick.timestampMillis)
        val minute = tick.timestampMillis / 60_000L * 60L
        var completed: MinuteBar? = null
        bars.compute(tick.symbol) { _, previous ->
            if (previous == null || previous.minuteEpochSeconds != minute) {
                completed = previous
                val volume = tick.volume.coerceAtLeast(0.0)
                MinuteBar(tick.symbol, minute, tick.price, tick.price, tick.price, tick.price, volume,
                    if (volume > 0.0) VolumeStatus.REPORTED else VolumeStatus.ZERO)
            } else {
                val tickVolume = tick.volume.coerceAtLeast(0.0)
                previous.copy(
                    high = maxOf(previous.high, tick.price),
                    low = minOf(previous.low, tick.price),
                    close = tick.price,
                    volume = previous.volume + tickVolume,
                    volumeStatus = if (previous.volume + tickVolume > 0.0) VolumeStatus.REPORTED else VolumeStatus.ZERO
                )
            }
        }
        completed?.let(onBar::accept)
    }
}
