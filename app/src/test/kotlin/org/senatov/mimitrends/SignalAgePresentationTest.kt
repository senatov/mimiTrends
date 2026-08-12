package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SignalAgePresentationTest {
    @Test fun `removes pattern description from minute age`() {
        assertEquals("16m", SignalAgePresentation.label("16m recovery"))
    }

    @Test fun `keeps session unit without strategy description`() {
        assertEquals("2 sessions", SignalAgePresentation.label("2 sessions steady"))
    }
}
