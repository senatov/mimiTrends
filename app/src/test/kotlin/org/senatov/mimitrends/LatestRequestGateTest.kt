package org.senatov.mimitrends

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
