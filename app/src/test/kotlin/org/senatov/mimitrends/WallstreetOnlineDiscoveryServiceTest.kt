package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.marketdata.WallstreetOnlineMover
import kotlin.test.assertEquals

class WallstreetOnlineDiscoveryServiceTest {
    @Test fun `refreshes the table every time and resolves a path only while it remains listed`() {
        var table = listOf(mover("/aktien/micron-aktie", "Micron Technology"))
        val queries = mutableListOf<String>()
        val service = WallstreetOnlineDiscoveryService(
            movers = { table },
            resolve = { name -> queries += name; if (name.startsWith("Micron")) "MU" else "LITE" }
        )

        assertEquals(listOf("MU"), service.discover())
        assertEquals(listOf("MU"), service.discover())
        table = listOf(mover("/aktien/lumentum-aktie", "Lumentum Holdings"))
        assertEquals(listOf("LITE"), service.discover())
        table = listOf(mover("/aktien/micron-aktie", "Micron Technology"))
        assertEquals(listOf("MU"), service.discover())

        assertEquals(listOf("Micron Technology", "Lumentum Holdings", "Micron Technology"), queries)
    }

    private fun mover(path: String, name: String) = WallstreetOnlineMover(name, path, 1.0, 1.0)
}
