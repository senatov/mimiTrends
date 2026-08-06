package org.senatov.mimitrends

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import kotlin.test.assertEquals

class MarketObservationBusTest {
    @Test
    fun `delivers observations published before collection starts`() = runBlocking {
        val bus = MarketObservationBus()
        val observation = observation()

        bus.publish(observation)
        val received = async { bus.observations.first() }.await()

        assertEquals(observation, received)
        bus.close()
    }

    private fun observation(): ProviderMinuteBar {
        val bar = MinuteBar("HEN3.DE", 0L, 79.22, 79.22, 79.22, 79.22, 0.0)
        return ProviderMinuteBar("TRADEGATE", "HEN3.DE", "DE0006048432", "XGAT", "EUR", bar, 1_234L)
    }
}
