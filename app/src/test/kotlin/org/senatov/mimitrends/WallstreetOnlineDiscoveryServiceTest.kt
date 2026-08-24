package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.marketdata.WallstreetOnlineMover
import kotlin.test.assertEquals

class WallstreetOnlineDiscoveryServiceTest {
    @Test fun `refreshes the activity table every ten minutes`() {
        var table = listOf(mover("/aktien/micron-aktie", "Micron Technology"))
        var now = 0L
        val queries = mutableListOf<String>()
        val service = WallstreetOnlineDiscoveryService(
            movers = { table },
            resolve = { name -> queries += name; if (name.startsWith("Micron")) "MU" else "LITE" },
            nowMillis = { now }
        )

        assertEquals(listOf("MU"), service.discover())
        table = listOf(mover("/aktien/lumentum-aktie", "Lumentum Holdings"))
        now += 10 * 60_000L - 1L
        assertEquals(listOf("MU"), service.discover())
        now += 1L
        assertEquals(listOf("LITE"), service.discover())
        table = listOf(mover("/aktien/micron-aktie", "Micron Technology"))
        now += 10 * 60_000L
        assertEquals(listOf("MU"), service.discover())

        assertEquals(listOf("Micron Technology", "Lumentum Holdings", "Micron Technology"), queries)
    }

    private fun mover(path: String, name: String) = WallstreetOnlineMover(name, path, 1.0, 1.0)
}
