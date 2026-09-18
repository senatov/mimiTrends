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

class SignalAgePresentationTest {
    @Test
    fun `formats a current signal clearly`() {
        assertEquals("00:00", SignalAgePresentation.label(0))
    }

    @Test
    fun `formats actual signal age in minutes`() {
        assertEquals("02:14", SignalAgePresentation.label(134))
    }
}