package org.senatov.mimitrends.signals

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignalPatternTextTest {
    @Test
    fun `measurement follows rendered pattern rows`() {
        val parsed = SignalPatternText.parse("Early recovery ↑ · recovery watch · weak volume")

        assertEquals(
            "Early recovery ↑\n* Recovery watch * [4/10]\nweak volume",
            parsed.measurementText("[4/10]")
        )
    }

    @Test
    fun `moves signal qualifiers to a second line`() {
        val content = SignalPatternText.parse("Momentum 3m ↓ · relaxed · cooling")

        assertEquals("Momentum 3m ↓", content.primary)
        assertEquals("relaxed · cooling", content.qualifiers)
        assertNull(content.watchLabel)
    }

    @Test
    fun `keeps a simple signal on one line`() {
        val content = SignalPatternText.parse("Recovery rise ↑")

        assertEquals("Recovery rise ↑", content.primary)
        assertNull(content.qualifiers)
        assertNull(content.watchLabel)
    }

    @Test
    fun `extracts a prominent recovery watch label`() {
        val content = SignalPatternText.parse("Recovery rise ↑ · watch")

        assertEquals("Recovery rise ↑", content.primary)
        assertNull(content.qualifiers)
        assertEquals("* Recovery watch *", content.watchLabel)
    }

    @Test
    fun `keeps non-watch qualifiers below an oversold badge`() {
        val content = SignalPatternText.parse("Oversold decline ↓ · watch · bottom unconfirmed")

        assertEquals("* Oversold watch *", content.watchLabel)
        assertEquals("bottom unconfirmed", content.qualifiers)
    }
}