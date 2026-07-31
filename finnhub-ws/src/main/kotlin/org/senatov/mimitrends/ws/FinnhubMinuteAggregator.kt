package org.senatov.mimitrends.ws

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.TradeTick
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
                MinuteBar(tick.symbol, minute, tick.price, tick.price, tick.price, tick.price, tick.volume.coerceAtLeast(0.0))
            } else {
                previous.copy(
                    high = maxOf(previous.high, tick.price),
                    low = minOf(previous.low, tick.price),
                    close = tick.price,
                    volume = previous.volume + tick.volume.coerceAtLeast(0.0)
                )
            }
        }
        completed?.let(onBar::accept)
    }
}
