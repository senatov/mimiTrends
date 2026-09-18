package org.senatov.mimitrends.shared

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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LatestRequestGateTest {
    @Test
    fun `rejects a late response after rapid instrument switching`() {
        val gate = LatestRequestGate<String>()
        val caterpillar = gate.begin("CAT")
        val lamResearch = gate.begin("LRCX")

        assertFalse(gate.accepts(caterpillar, "LRCX"))
        assertTrue(gate.accepts(lamResearch, "LRCX"))
    }

    @Test
    fun `rejects an outstanding response after invalidation`() {
        val gate = LatestRequestGate<String>()
        val request = gate.begin("SAP.DE")

        gate.invalidate()

        assertFalse(gate.accepts(request, "SAP.DE"))
    }
}
