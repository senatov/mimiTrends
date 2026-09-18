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
