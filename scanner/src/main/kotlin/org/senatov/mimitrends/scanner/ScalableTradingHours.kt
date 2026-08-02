package org.senatov.mimitrends.scanner

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object ScalableTradingHours {
    data class VenueHours(val venue: String, val opensAt: Instant, val closesAt: Instant)

    fun nextSessions(instant: Instant = Instant.now()): List<VenueHours> {
        val zone = ZoneId.of("Europe/Berlin")
        var date = instant.atZone(zone).toLocalDate()
        while (date.dayOfWeek in WEEKEND ||
            !ZonedDateTime.of(date, CLOSE, zone).toInstant().isAfter(instant)) date = date.plusDays(1)
        val opensAt = ZonedDateTime.of(date, OPEN, zone).toInstant()
        val closesAt = ZonedDateTime.of(date, CLOSE, zone).toInstant()
        return listOf(VenueHours("GETTEX", opensAt, closesAt), VenueHours("EIX", opensAt, closesAt))
    }

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
    private val OPEN = LocalTime.of(7, 30)
    private val CLOSE = LocalTime.of(23, 0)
}
