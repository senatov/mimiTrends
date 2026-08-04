package org.senatov.mimitrends

import org.senatov.mimitrends.scanner.MarketCalendar
import java.util.concurrent.ConcurrentMap

internal class FeedStatusResolver(private val liveTicks: ConcurrentMap<String, Long>) {
    fun status(symbol: String): String {
        val liveAt = liveTicks[symbol]
        if (liveAt != null && System.currentTimeMillis() - liveAt <= 180_000L) return "LIVE"
        if (!MarketCalendar.isOpen(symbol)) return "CACHE"
        return when {
            !symbol.contains('.') -> "YAHOO RT"
            symbol.endsWith(".MI") -> "DELAYED 20m"
            symbol.endsWith(".DE") || symbol.endsWith(".PA") || symbol.endsWith(".AS") -> "DELAYED 15m"
            symbol.endsWith(".HE") -> "YAHOO RT"
            else -> "YAHOO"
        }
    }
}
