package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrokerTradeMatcherTest {
    @Test fun `pairs partial executions using fifo and retains open quantity`() {
        val executions = listOf(
            execution(1, 100, "Buy", 10.0, 20.0, -200.0, fee = 1.0),
            execution(2, 200, "Buy", 5.0, 22.0, -110.0),
            execution(3, 300, "Sell", 12.0, 25.0, 300.0, fee = 1.2)
        )
        val trades = BrokerTradeMatcher.pair("TEST", executions)
        assertEquals(3, trades.size)
        assertEquals(10.0, trades[0].quantity)
        assertEquals(2.0, trades[1].quantity)
        assertEquals(3.0, trades[2].quantity)
        assertEquals(48.0, requireNotNull(trades[0].profitAmount), 0.000_001)
        assertNull(trades[2].exitEpochSeconds)
    }

    @Test fun `matches scalable short name to provider company name`() {
        assertTrue(BrokerTradeMatcher.matches("Apple", "Apple Inc."))
        assertTrue(BrokerTradeMatcher.matches("Amazon.com", "Amazon.com, Inc."))
    }

    private fun execution(
        id: Long, epoch: Long, type: String, shares: Double, price: Double, amount: Double, fee: Double = 0.0
    ) = BrokerExecution(id, epoch, "Test Corp", type, "TEST-ISIN", shares, price, amount, fee, 0.0, "EUR")
}
