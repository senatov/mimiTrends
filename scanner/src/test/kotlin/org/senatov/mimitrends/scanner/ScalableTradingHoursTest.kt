package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

class ScalableTradingHoursTest {
    @Test fun `reports regular gettex and eix sessions in berlin time`() {
        val sessions = ScalableTradingHours.nextSessions(Instant.parse("2026-08-03T04:00:00Z"))
        val berlin = ZoneId.of("Europe/Berlin")
        assertEquals(listOf("GETTEX", "EIX"), sessions.map { it.venue })
        assertEquals("07:30", sessions.first().opensAt.atZone(berlin).toLocalTime().toString())
        assertEquals("23:00", sessions.first().closesAt.atZone(berlin).toLocalTime().toString())
    }
}
