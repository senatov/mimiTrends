package org.senatov.mimitrends.application

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FinnhubSubscriptionManagerTest {
    @Test
    fun `adds newly discovered US symbols and removes departed symbols`() {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val manager = FinnhubSubscriptionManager(added::add, removed::add)

        manager.replace(listOf("AAPL", "SAP.DE", "MU"))
        manager.replace(listOf("MU", "NVDA", "SIE.DE"))

        assertEquals(listOf("AAPL", "MU", "NVDA"), added)
        assertEquals(listOf("AAPL"), removed)
    }

    @Test
    fun `replays active subscriptions after client restart`() {
        val added = mutableListOf<String>()
        val manager = FinnhubSubscriptionManager(added::add, {})

        manager.replace(listOf("aapl"))
        manager.reset()
        manager.replace(listOf("aapl"))

        assertEquals(listOf("AAPL", "AAPL"), added)
    }
}
