package org.senatov.mimitrends

import org.senatov.mimitrends.scanner.MarketCalendar

internal object ScanMarketEligibility {
    fun isActive(
        symbol: String,
        liveTickAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (MarketCalendar.isOpen(symbol, java.time.Instant.ofEpochMilli(nowMillis))) return true
        if (symbol.contains('.')) return false
        val tickAt = liveTickAtMillis ?: return false
        return nowMillis - tickAt in -FUTURE_TOLERANCE_MILLIS..MAX_LIVE_TICK_AGE_MILLIS
    }

    private const val MAX_LIVE_TICK_AGE_MILLIS = 3 * 60_000L
    private const val FUTURE_TOLERANCE_MILLIS = 60_000L
}
