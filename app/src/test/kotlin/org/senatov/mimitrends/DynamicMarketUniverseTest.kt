package org.senatov.mimitrends

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.ScannerCriteria

class DynamicMarketUniverseTest {
    @Test
    fun `uses only the configured broker universe`() {
        val selection = DynamicMarketUniverse().select(
            ScannerCriteria(symbols = listOf("AAPL", "MSFT", "AAPL"))
        )

        assertEquals(listOf("AAPL", "MSFT"), selection.symbols)
        assertEquals(emptyList<String>(), selection.discovered)
    }

    @Test
    fun `excludes markets with unwanted country fees`() {
        val selection = DynamicMarketUniverse().select(
            ScannerCriteria(symbols = listOf("TTE.PA", "AIR.PA", "ENEL.MI", "NOKIA.HE", "NOVO-B.CO", "SAP.DE"))
        )

        assertEquals(listOf("SAP.DE"), selection.symbols)
    }

    @Test
    fun `adds current external movers to every selected universe`() {
        val selection = DynamicMarketUniverse(discover = { listOf("MU", "SRT3.DE", "MU") }).select(
            ScannerCriteria(symbols = listOf("AAPL"))
        )

        assertEquals(listOf("AAPL", "MU", "SRT3.DE"), selection.symbols)
        assertEquals(listOf("MU", "SRT3.DE"), selection.discovered)
    }

    @Test
    fun `keeps a configured core and fills dynamic slots per region`() {
        val us = (1..60).map { "U$it" }
        val europe = (1..60).map { "E$it.DE" }

        val selection = DynamicMarketUniverse(discover = { us + europe }).select(
            ScannerCriteria(symbols = us.reversed() + europe.reversed())
        )

        assertEquals(120, selection.symbols.size)
        assertEquals(us.reversed().take(35), selection.symbols.take(35))
        assertEquals(europe.reversed().take(35), selection.symbols.drop(60).take(35))
        assertEquals(36, selection.ranks["U1"])
        assertEquals(36, selection.ranks["E1.DE"])
    }

    @Test
    fun `reuses a stable universe until the four hour refresh boundary`() {
        var now = 1_000L
        var discovered = listOf("MU", "NVDA")
        val universe = DynamicMarketUniverse({ discovered }, { now })
        val criteria = ScannerCriteria(symbols = listOf("AAPL"))

        assertEquals(listOf("AAPL", "MU", "NVDA"), universe.select(criteria).symbols)
        discovered = listOf("AMD", "META")
        assertEquals(listOf("AAPL", "MU", "NVDA"), universe.select(criteria).symbols)
        now += 4 * 60 * 60 * 1_000L
        assertEquals(listOf("AAPL", "AMD", "META"), universe.select(criteria).symbols)
    }

    @Test
    fun `limits rotation to five symbols per region`() {
        var now = 0L
        var discovered = (1..50).map { "OLD$it" }
        val universe = DynamicMarketUniverse({ discovered }, { now })
        val criteria = ScannerCriteria(symbols = emptyList())

        val initial = universe.select(criteria).symbols
        discovered = (1..50).map { "NEW$it" }
        now += 4 * 60 * 60 * 1_000L
        val refreshed = universe.select(criteria).symbols

        assertEquals(5, refreshed.count { it.startsWith("NEW") })
        assertEquals(45, refreshed.count(initial::contains))
    }

    @Test
    fun `activity and freshness can outrank external discovery order`() {
        val now = 10_000L
        val core = (1..35).map { "CORE$it" }
        val universe = DynamicMarketUniverse({ listOf("SLOW", "FAST") }, { now })
        universe.record(
            listOf(
                TestScanResult.create(symbol = "FAST").copy(
                    sessionTurnover = 100_000_000.0,
                    windowChangePercent = 3.5,
                    analysisUpdatedAtMillis = now
                )
            )
        )

        val selection = universe.select(ScannerCriteria(symbols = core))

        assertEquals(36, selection.ranks["FAST"])
        assertEquals(37, selection.ranks["SLOW"])
    }
}
