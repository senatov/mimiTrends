package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketCalendarTest {
    @Test fun `session start uses the current European market opening`() {
        assertEquals(Instant.parse("2026-08-03T07:00:00Z"),
            MarketCalendar.sessionStart("MC.PA", Instant.parse("2026-08-03T14:00:00Z")))
    }

    @Test fun `session start before Monday opening uses previous Friday`() {
        assertEquals(Instant.parse("2026-07-31T07:00:00Z"),
            MarketCalendar.sessionStart("MC.PA", Instant.parse("2026-08-03T06:00:00Z")))
    }

    @Test fun `US market observes regular session in New York time`() {
        assertTrue(MarketCalendar.isOpen("AAPL", Instant.parse("2026-08-03T14:00:00Z")))
        assertFalse(MarketCalendar.isOpen("AAPL", Instant.parse("2026-08-03T21:00:00Z")))
    }

    @Test fun `US market is closed on observed Independence Day`() {
        assertFalse(MarketCalendar.isOpen("AAPL", Instant.parse("2026-07-03T15:00:00Z")))
        assertEquals(
            Instant.parse("2026-07-06T13:30:00Z"),
            MarketCalendar.nextOpening("AAPL", Instant.parse("2026-07-03T15:00:00Z"))
        )
    }

    @Test fun `Xetra is closed on Labor Day and resumes after weekend`() {
        assertFalse(MarketCalendar.isOpen("VNA.DE", Instant.parse("2026-05-01T10:00:00Z")))
        assertEquals(
            Instant.parse("2026-05-04T07:00:00Z"),
            MarketCalendar.nextOpening("VNA.DE", Instant.parse("2026-05-01T10:00:00Z"))
        )
    }

    @Test fun `earliest selected market opening wins`() {
        val opening = requireNotNull(MarketCalendar.nextOpening(
            listOf("AAPL", "VNA.DE"), Instant.parse("2026-08-03T06:30:00Z")
        ))
        assertEquals("VNA.DE", opening.symbol)
        assertEquals(Instant.parse("2026-08-03T07:00:00Z"), opening.instant)
    }

    @Test fun `trading hours are unique per market and use next sessions`() {
        val hours = MarketCalendar.nextTradingHours(
            listOf("AAPL", "MSFT", "VNA.DE"), Instant.parse("2026-08-02T12:00:00Z")
        )
        assertEquals(listOf("XETRA", "US"), hours.map(MarketCalendar.TradingHours::market))
        assertEquals(Instant.parse("2026-08-03T07:00:00Z"), hours[0].opensAt)
        assertEquals(Instant.parse("2026-08-03T15:30:00Z"), hours[0].closesAt)
        assertEquals(Instant.parse("2026-08-03T13:30:00Z"), hours[1].opensAt)
        assertEquals(Instant.parse("2026-08-03T20:00:00Z"), hours[1].closesAt)
    }
}
