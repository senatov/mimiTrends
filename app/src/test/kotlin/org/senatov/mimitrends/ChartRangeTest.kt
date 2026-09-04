package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class ChartRangeTest {
    @Test fun `defines every selectable chart range`() {
        assertEquals(listOf("1D", "5D", "1M", "3M", "6M", "1Y"), ChartRange.values)
        assertEquals(listOf(1L, 5L, 30L, 90L, 180L, 365L), ChartRange.values.map(ChartRange::days))
    }

    @Test fun `normalizes unsupported persisted ranges`() {
        assertEquals("1M", ChartRange.normalize("1M"))
        assertEquals("3M", ChartRange.normalize("unsupported"))
    }

    @Test
    fun `one day starts at the instruments current market date`() {
        val now = Instant.parse("2026-09-04T07:25:00Z")

        assertEquals(
            expected = Instant.parse("2026-09-03T22:00:00Z").epochSecond,
            actual = ChartRange.fromEpochSeconds("1D", "HEN3.DE", now)
        )
        assertEquals(
            expected = Instant.parse("2026-09-04T04:00:00Z").epochSecond,
            actual = ChartRange.fromEpochSeconds("1D", "AAPL", now)
        )
    }

    @Test
    fun `longer ranges remain rolling calendar durations`() {
        val now = Instant.parse("2026-09-04T07:25:00Z")

        assertEquals(
            expected = now.minusSeconds(5 * 86_400L).epochSecond,
            actual = ChartRange.fromEpochSeconds("5D", "HEN3.DE", now)
        )
    }
}
