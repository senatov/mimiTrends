package org.senatov.mimitrends

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
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

    private fun observation() = MarketPriceObservation("TRADEGATE", "HEN3.DE", 79.22, 1_234L)
}
