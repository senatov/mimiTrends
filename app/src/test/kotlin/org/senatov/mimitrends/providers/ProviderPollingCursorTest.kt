package org.senatov.mimitrends.providers

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ProviderPollingCursorTest {
    @Test
    fun `keeps the next symbol after a universe rotation`() {
        assertEquals(2, ProviderPollingCursor.nextIndex(
            listOf("A.DE", "B.DE", "C.DE"), 1,
            listOf("C.DE", "D.DE", "B.DE", "E.DE")
        ))
    }

    @Test
    fun `uses a bounded position when the next symbol leaves`() {
        assertEquals(1, ProviderPollingCursor.nextIndex(listOf("A.DE", "B.DE", "C.DE"), 2, listOf("D.DE", "E.DE")))
        assertEquals(0, ProviderPollingCursor.nextIndex(listOf("A.DE"), 0, emptyList()))
    }
}
