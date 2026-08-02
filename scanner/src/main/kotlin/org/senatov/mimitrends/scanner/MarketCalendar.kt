package org.senatov.mimitrends.scanner

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import org.senatov.mimitrends.model.MarketTimeZone

object MarketCalendar {
    data class Opening(val symbol: String, val instant: Instant)
    data class TradingHours(val market: String, val opensAt: Instant, val closesAt: Instant)

    fun isOpen(symbol: String, instant: Instant = Instant.now()): Boolean {
        val market = marketFor(symbol)
        val local = instant.atZone(market.zone)
        return isTradingDay(market, local.toLocalDate()) &&
            !local.toLocalTime().isBefore(market.open) && local.toLocalTime().isBefore(market.close)
    }

    fun nextOpening(symbol: String, instant: Instant = Instant.now()): Instant {
        val market = marketFor(symbol)
        var date = instant.atZone(market.zone).toLocalDate()
        repeat(370) {
            if (isTradingDay(market, date)) {
                val opening = ZonedDateTime.of(date, market.open, market.zone).toInstant()
                if (opening.isAfter(instant)) return opening
                val close = ZonedDateTime.of(date, market.close, market.zone).toInstant()
                if (instant.isBefore(close)) return instant
            }
            date = date.plusDays(1)
        }
        error("No market opening found for $symbol within 370 days")
    }

    fun nextOpening(symbols: Collection<String>, instant: Instant = Instant.now()): Opening? = symbols
        .map { Opening(it, nextOpening(it, instant)) }
        .minByOrNull(Opening::instant)

    fun nextTradingHours(symbols: Collection<String>, instant: Instant = Instant.now()): List<TradingHours> = symbols
        .map(::marketFor)
        .distinctBy(Market::kind)
        .map { market ->
            var date = instant.atZone(market.zone).toLocalDate()
            while (!isTradingDay(market, date) || !ZonedDateTime.of(date, market.close, market.zone).toInstant().isAfter(instant)) {
                date = date.plusDays(1)
            }
            TradingHours(
                market = market.label,
                opensAt = ZonedDateTime.of(date, market.open, market.zone).toInstant(),
                closesAt = ZonedDateTime.of(date, market.close, market.zone).toInstant()
            )
        }
        .sortedBy(TradingHours::opensAt)

    private fun isTradingDay(market: Market, date: LocalDate): Boolean =
        date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) && date !in holidays(market, date.year)

    private fun holidays(market: Market, year: Int): Set<LocalDate> {
        val easter = easterSunday(year)
        return when (market.kind) {
            Kind.US -> setOf(
                observed(LocalDate.of(year, Month.JANUARY, 1)),
                observed(LocalDate.of(year + 1, Month.JANUARY, 1)),
                nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3),
                nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3),
                easter.minusDays(2),
                LocalDate.of(year, Month.MAY, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)),
                observed(LocalDate.of(year, Month.JUNE, 19)),
                observed(LocalDate.of(year, Month.JULY, 4)),
                nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1),
                nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4),
                observed(LocalDate.of(year, Month.DECEMBER, 25))
            )
            Kind.XETRA -> setOf(
                LocalDate.of(year, Month.JANUARY, 1), easter.minusDays(2), easter.plusDays(1),
                LocalDate.of(year, Month.MAY, 1), LocalDate.of(year, Month.DECEMBER, 24),
                LocalDate.of(year, Month.DECEMBER, 25), LocalDate.of(year, Month.DECEMBER, 26),
                LocalDate.of(year, Month.DECEMBER, 31)
            )
            Kind.EURONEXT -> setOf(
                LocalDate.of(year, Month.JANUARY, 1), easter.minusDays(2), easter.plusDays(1),
                LocalDate.of(year, Month.MAY, 1), LocalDate.of(year, Month.DECEMBER, 25),
                LocalDate.of(year, Month.DECEMBER, 26)
            )
            Kind.HELSINKI -> setOf(
                LocalDate.of(year, Month.JANUARY, 1), LocalDate.of(year, Month.JANUARY, 6),
                easter.minusDays(2), easter.plusDays(1), LocalDate.of(year, Month.MAY, 1),
                LocalDate.of(year, Month.DECEMBER, 24), LocalDate.of(year, Month.DECEMBER, 25),
                LocalDate.of(year, Month.DECEMBER, 26)
            )
        }
    }

    private fun marketFor(symbol: String): Market = when {
        symbol.endsWith(".DE", true) -> Market(Kind.XETRA, "XETRA", MarketTimeZone.forSymbol(symbol), LocalTime.of(9, 0), LocalTime.of(17, 30))
        symbol.endsWith(".HE", true) -> Market(Kind.HELSINKI, "HELSINKI", MarketTimeZone.forSymbol(symbol), LocalTime.of(10, 0), LocalTime.of(18, 30))
        symbol.contains('.') -> Market(Kind.EURONEXT, "EURONEXT", MarketTimeZone.forSymbol(symbol), LocalTime.of(9, 0), LocalTime.of(17, 30))
        else -> Market(Kind.US, "US", MarketTimeZone.forSymbol(symbol), LocalTime.of(9, 30), LocalTime.of(16, 0))
    }

    private fun observed(date: LocalDate): LocalDate = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.minusDays(1)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun nthWeekday(year: Int, month: Month, day: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day))

    // Meeus/Jones/Butcher Gregorian Easter algorithm.
    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private data class Market(val kind: Kind, val label: String, val zone: ZoneId, val open: LocalTime, val close: LocalTime)
    private enum class Kind { US, XETRA, EURONEXT, HELSINKI }
}
