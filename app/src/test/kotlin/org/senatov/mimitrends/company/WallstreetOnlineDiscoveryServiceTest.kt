package org.senatov.mimitrends.company

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.marketdata.WallstreetOnlineMover
import kotlin.test.assertEquals

class WallstreetOnlineDiscoveryServiceTest {
    @Test
    fun `refreshes the activity table every thirty minutes`() {
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
        now += 30 * 60_000L - 1L
        assertEquals(listOf("MU"), service.discover())
        now += 1L
        assertEquals(listOf("LITE"), service.discover())
        table = listOf(mover("/aktien/micron-aktie", "Micron Technology"))
        now += 30 * 60_000L
        assertEquals(listOf("MU"), service.discover())

        assertEquals(listOf("Micron Technology", "Lumentum Holdings", "Micron Technology"), queries)
    }

    @Test
    fun `limits discovery resolution to twenty candidates`() {
        val queries = mutableListOf<String>()
        val service = WallstreetOnlineDiscoveryService(
            movers = { (1..35).map { mover("/aktien/test-$it", "Company $it") } },
            resolve = { name -> queries += name; "TEST${queries.size}" }
        )

        assertEquals(20, service.discover().size)
        assertEquals(20, queries.size)
    }

    private fun mover(path: String, name: String) = WallstreetOnlineMover(name, path, 1.0, 1.0)
}
