package org.senatov.mimitrends.charts

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TradeCardConnectorTest {
    @Test fun `normalizes time and price before calculating a curved connector`() {
        val trade = TradeCardConnector.Bounds(0.0, 100.0, 480_000.0, 110.0)
        val card = TradeCardConnector.Bounds(120_000.0, 120.0, 720_000.0, 124.0)

        val geometry = TradeCardConnector.geometry(trade, card, 60_000.0, 10.0)

        assertTrue(geometry.start.x in trade.left..trade.right)
        assertEquals(trade.top, geometry.start.y)
        assertTrue(geometry.end.x in card.left..card.right)
        assertEquals(card.bottom, geometry.end.y)
        assertTrue(geometry.control1.y in 90.0..150.0)
        assertTrue(geometry.control2.y in 90.0..150.0)
    }

    @Test fun `connector geometry scales consistently across chart units`() {
        val first = TradeCardConnector.geometry(
            TradeCardConnector.Bounds(0.0, 0.0, 8.0, 1.0),
            TradeCardConnector.Bounds(2.0, 2.0, 12.0, 2.4),
            1.0,
            1.0
        )
        val scaled = TradeCardConnector.geometry(
            TradeCardConnector.Bounds(0.0, 0.0, 480_000.0, 10.0),
            TradeCardConnector.Bounds(120_000.0, 20.0, 720_000.0, 24.0),
            60_000.0,
            10.0
        )

        assertEquals(first.start.x, scaled.start.x / 60_000.0, 0.000_001)
        assertEquals(first.start.y, scaled.start.y / 10.0, 0.000_001)
        assertEquals(first.control1.x, scaled.control1.x / 60_000.0, 0.000_001)
        assertEquals(first.control1.y, scaled.control1.y / 10.0, 0.000_001)
        assertEquals(first.end.x, scaled.end.x / 60_000.0, 0.000_001)
        assertEquals(first.end.y, scaled.end.y / 10.0, 0.000_001)
    }
}
