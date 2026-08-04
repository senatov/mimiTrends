package org.senatov.mimitrends

import org.senatov.mimitrends.scanner.MarketCalendar
import org.senatov.mimitrends.scanner.ScalableTradingHours
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object MarketHoursFormatter {
    fun nextOpening(opening: MarketCalendar.Opening): String {
        val local = opening.instant.atZone(ZoneId.systemDefault())
        return "${opening.symbol} · ${MARKET_OPEN.format(local)}"
    }

    fun priceData(symbols: Collection<String>, instant: Instant, userZone: ZoneId): List<String> =
        MarketCalendar.nextTradingHours(symbols, instant).map { hours ->
            val open = hours.opensAt.atZone(userZone)
            val close = hours.closesAt.atZone(userZone)
            val closeText = if (close.toLocalDate() == open.toLocalDate()) TIME.format(close) else DAY_TIME.format(close)
            "${hours.market}  ${DAY_TIME.format(open)}–$closeText"
        }

    fun scalable(instant: Instant, userZone: ZoneId): List<String> =
        ScalableTradingHours.nextSessions(instant).map { hours ->
            val open = hours.opensAt.atZone(userZone)
            val close = hours.closesAt.atZone(userZone)
            "${hours.venue}  ${DAY_TIME.format(open)}–${TIME.format(close)}"
        }

    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val MARKET_OPEN = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm z")
}
