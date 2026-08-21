package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanCyclePlannerTest {
    @Test fun `alternates regions and rotates the leading symbol`() {
        val planner = ScanCyclePlanner()
        val symbols = listOf("A", "B", "C.DE", "D.DE")

        val first = planner.order(symbols)
        val second = planner.order(symbols)

        assertEquals(listOf("A", "C.DE", "B", "D.DE"), first)
        assertEquals(listOf("D.DE", "B", "C.DE", "A"), second)
        assertTrue(first.first() != second.first())
    }

    @Test fun `keeps recent candidates ahead of the ordinary rotation`() {
        val planner = ScanCyclePlanner()
        planner.replacePriority(listOf("C.DE"))

        repeat(3) {
            assertEquals("C.DE", planner.order(listOf("A", "B", "C.DE", "D.DE")).first())
        }
        assertTrue(planner.order(listOf("A", "B", "C.DE", "D.DE")).first() != "C.DE")
    }

    @Test fun `bounds large cycles and rotates through the full universe`() {
        val planner = ScanCyclePlanner(maximumSymbolsPerCycle = 4)
        val symbols = (1..12).map { "S$it" }

        val covered = buildSet {
            repeat(3) { addAll(planner.order(symbols)) }
        }

        assertEquals(symbols.toSet(), covered)
    }

    @Test fun `rotates both regions without starving either one`() {
        val planner = ScanCyclePlanner(maximumSymbolsPerCycle = 4)
        val symbols = (1..6).map { "U$it" } + (1..6).map { "E$it.DE" }

        val covered = buildSet {
            repeat(3) { addAll(planner.order(symbols)) }
        }

        assertEquals(symbols.toSet(), covered)
    }
}
