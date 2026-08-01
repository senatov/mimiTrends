package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketCalendarTest {
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
}
