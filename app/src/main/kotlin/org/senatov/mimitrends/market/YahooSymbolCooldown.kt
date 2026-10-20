package org.senatov.mimitrends.market

import java.util.concurrent.ConcurrentHashMap

internal class YahooSymbolCooldown(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val retryMillis: Long = 15 * 60_000L
) {
    private val unavailableUntil = ConcurrentHashMap<String, Long>()

    fun isCooling(symbol: String): Boolean = (unavailableUntil[symbol] ?: 0L) > nowMillis()

    fun pause(symbol: String) {
        unavailableUntil[symbol] = nowMillis() + retryMillis
    }
}
