package org.senatov.mimitrends

import org.junit.jupiter.api.Test
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
}
