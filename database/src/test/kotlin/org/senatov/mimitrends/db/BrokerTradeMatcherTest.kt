package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrokerTradeMatcherTest {
    @Test fun `orders executions by time and closes an existing position before reopening in the same second`() {
        val executions = listOf(
            execution(1, 100, "Buy", 10.0, 20.0, -200.0),
            execution(4, 200, "Buy", 10.0, 22.0, -220.0),
            execution(2, 200, "Sell", 10.0, 21.0, 210.0),
            execution(3, 200, "Sell", 10.0, 23.0, 230.0)
        )

        val trades = BrokerTradeMatcher.pair("TEST", executions)

        assertEquals(2, trades.size)
        assertEquals(10.0, trades[0].quantity)
        assertEquals(100L, trades[0].entryEpochSeconds)
        assertEquals(200L, trades[0].exitEpochSeconds)
        assertEquals(200L, trades[1].entryEpochSeconds)
        assertEquals(200L, trades[1].exitEpochSeconds)
        assertTrue(trades.zipWithNext().all { (first, second) ->
            requireNotNull(first.exitEpochSeconds) <= second.entryEpochSeconds
        })
    }

    @Test fun `matches scalable short name to provider company name`() {
        assertTrue(BrokerTradeMatcher.matches("Apple", "Apple Inc."))
        assertTrue(BrokerTradeMatcher.matches("Amazon.com", "Amazon.com, Inc."))
    }

    @Test fun `combines position increases before a full close`() {
        val executions = listOf(
            execution(1, 100, "Buy", 220.0, 15.546, -3420.12),
            execution(2, 200, "Buy", 5.0, 15.594, -77.97, 0.99),
            execution(3, 200, "Sell", 225.0, 15.508, 3489.30)
        )

        val trade = BrokerTradeMatcher.pair("SOFI", executions).single()

        assertEquals(225.0, trade.quantity)
        assertEquals(-9.78, trade.profitAmount!!, 0.000_001)
        assertEquals(200L, trade.exitEpochSeconds)
    }

    @Test fun `keeps distinct executions with the same timestamp and quantity`() {
        val executions = listOf(
            execution(1, 100, "Buy", 42.0, 78.0, -3276.0),
            execution(2, 200, "Sell", 42.0, 79.08, 3321.36),
            execution(3, 200, "Buy", 42.0, 79.1, -3322.2),
            execution(4, 200, "Sell", 42.0, 79.27, 3329.34)
        )

        val trades = BrokerTradeMatcher.pair("INTC", executions)

        assertEquals(2, trades.size)
        assertTrue(trades.all { !it.isOpen })
        assertEquals(45.36, trades[0].profitAmount!!, 0.000_001)
        assertEquals(7.14, trades[1].profitAmount!!, 0.000_001)
    }

    @Test fun `reports a sell that cannot be reconciled with an open position`() {
        val reconciliation = BrokerTradeMatcher.reconcile(
            "TEST", listOf(execution(1, 100, "Sell", 10.0, 20.0, 200.0))
        )

        assertEquals(0, reconciliation.trades.size)
        assertEquals(1, reconciliation.unmatchedSells)
    }

    @Test fun `corrects an exported sell followed by its buy`() {
        val reconciliation = BrokerTradeMatcher.reconcile("LRCX", listOf(
            execution(1, 100, "Sell", 12.0, 272.95, 3275.40),
            execution(2, 123, "Buy", 12.0, 274.26, -3291.15)
        ))

        val trade = reconciliation.trades.single()
        assertEquals(1, reconciliation.correctedOrder)
        assertEquals(0, reconciliation.unmatchedSells)
        assertEquals(100L, trade.entryEpochSeconds)
        assertEquals(100L, trade.exitEpochSeconds)
        assertEquals(-15.75, trade.profitAmount!!, 0.000_001)
    }

    @Test fun `does not manufacture an open position from a flat same-second batch`() {
        val reconciliation = BrokerTradeMatcher.reconcile("IFX.DE", listOf(
            execution(1, 100, "Buy", 52.0, 63.55, -3304.60),
            execution(2, 100, "Sell", 54.0, 63.75, 3442.50),
            execution(3, 100, "Buy", 54.0, 64.46, -3480.84),
            execution(4, 200, "Buy", 52.0, 63.51, -3302.52),
            execution(5, 200, "Sell", 52.0, 63.89, 3322.28),
            execution(6, 300, "Sell", 52.0, 63.36, 3294.72)
        ))

        assertEquals(0, reconciliation.unmatchedSells)
        assertTrue(reconciliation.trades.none { it.isOpen })
        assertEquals(2, reconciliation.trades.size)
    }

    private fun execution(
        id: Long, epoch: Long, type: String, shares: Double, price: Double, amount: Double, fee: Double = 0.0
    ) = BrokerExecution(id, epoch, "Test Corp", type, "TEST-ISIN", shares, price, amount, fee, 0.0, "EUR")
}
