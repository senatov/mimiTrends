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

    private fun execution(
        id: Long, epoch: Long, type: String, shares: Double, price: Double, amount: Double, fee: Double = 0.0
    ) = BrokerExecution(id, epoch, "Test Corp", type, "TEST-ISIN", shares, price, amount, fee, 0.0, "EUR")
}
