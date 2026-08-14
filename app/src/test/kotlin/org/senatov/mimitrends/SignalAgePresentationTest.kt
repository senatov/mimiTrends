package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SignalAgePresentationTest {
    @Test fun `formats a current signal clearly`() {
        assertEquals("now", SignalAgePresentation.label(0))
    }

    @Test fun `formats actual signal age in minutes`() {
        assertEquals("134m", SignalAgePresentation.label(134))
    }
}
