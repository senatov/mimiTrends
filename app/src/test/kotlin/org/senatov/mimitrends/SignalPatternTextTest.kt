package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalPatternTextTest {
    @Test fun `moves signal qualifiers to a second line`() {
        val content = SignalPatternText.parse("Momentum 3m ↓ · relaxed · cooling")

        assertEquals("Momentum 3m ↓", content.primary)
        assertEquals("relaxed · cooling", content.qualifiers)
        assertNull(content.watchLabel)
    }

    @Test fun `keeps a simple signal on one line`() {
        val content = SignalPatternText.parse("Recovery rise ↑")

        assertEquals("Recovery rise ↑", content.primary)
        assertNull(content.qualifiers)
        assertNull(content.watchLabel)
    }

    @Test fun `extracts a prominent recovery watch label`() {
        val content = SignalPatternText.parse("Recovery rise ↑ · watch")

        assertEquals("Recovery rise ↑", content.primary)
        assertNull(content.qualifiers)
        assertEquals("* Recovery watch *", content.watchLabel)
    }

    @Test fun `keeps non-watch qualifiers below an oversold badge`() {
        val content = SignalPatternText.parse("Oversold decline ↓ · watch · bottom unconfirmed")

        assertEquals("* Oversold watch *", content.watchLabel)
        assertEquals("bottom unconfirmed", content.qualifiers)
    }
}
