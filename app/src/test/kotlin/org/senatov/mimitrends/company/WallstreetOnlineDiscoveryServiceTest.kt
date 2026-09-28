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
import org.senatov.mimitrends.marketdata.WallstreetOnlineCategory
import org.senatov.mimitrends.marketdata.WallstreetOnlineRankedMover
import kotlin.test.assertEquals

class WallstreetOnlineDiscoveryServiceTest {
    @Test
    fun `refreshes the activity table every thirty minutes`() {
        var table = listOf(mover("/aktien/micron-aktie", "Micron Technology"))
        var now = 0L
        val queries = mutableListOf<String>()
        val service = WallstreetOnlineDiscoveryService(
            rankings = { table },
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
    fun `limits discovery resolution to forty candidates`() {
        val queries = mutableListOf<String>()
        val service = WallstreetOnlineDiscoveryService(
            rankings = { (1..55).map { mover("/aktien/test-$it", "Company $it") } },
            resolve = { name -> queries += name; "TEST${queries.size}" }
        )

        assertEquals(40, service.discover().size)
        assertEquals(40, queries.size)
    }

    @Test
    fun `keeps flop and volume candidates alongside top performers`() {
        val rankings = (1..12).map { mover("/aktien/top-$it", "Top $it", WallstreetOnlineCategory.TOP, it) } +
                (1..12).map { mover("/aktien/flop-$it", "Flop $it", WallstreetOnlineCategory.FLOP, it) } +
                (1..12).map { mover("/aktien/volume-$it", "Volume $it", WallstreetOnlineCategory.MOST_TRADED, it) }
        val service = WallstreetOnlineDiscoveryService(
            rankings = { rankings }, resolve = { it.replace(' ', '_').uppercase() }
        )

        val symbols = service.discover()

        assertEquals(36, symbols.size)
        assertEquals(listOf("FLOP_1", "VOLUME_1", "TOP_1"), symbols.take(3))
        assertEquals(setOf(WallstreetOnlineCategory.FLOP), service.categories("FLOP_1"))
    }

    @Test
    fun `retains cached discovery and category when ranking refresh fails`() {
        var now = 0L
        var fail = false
        var calls = 0
        val service = WallstreetOnlineDiscoveryService(
            rankings = {
                calls++
                if (fail) error("provider unavailable")
                listOf(mover("/aktien/test", "Test", WallstreetOnlineCategory.FLOP))
            },
            resolve = { "TEST" }, nowMillis = { now }
        )

        assertEquals(listOf("TEST"), service.discover())
        fail = true
        now += 30 * 60_000L
        assertEquals(listOf("TEST"), service.discover())
        assertEquals(setOf(WallstreetOnlineCategory.FLOP), service.categories("TEST"))
        assertEquals(listOf("TEST"), service.discover())
        assertEquals(2, calls)
    }

    private fun mover(
        path: String, name: String,
        category: WallstreetOnlineCategory = WallstreetOnlineCategory.TOP,
        rank: Int = 1
    ) = WallstreetOnlineRankedMover(WallstreetOnlineMover(name, path, 1.0, 1.0), category, rank)
}