package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LangSchwarzMarketDataClientTest {
    @Test fun `parses a European listing with German prices and quote time`() {
        val html = """<table><tr>
            <td><div>ENER6Y</div></td><td><div><a href="/de/aktie/1240969">SIEMENS ENERGY AG NA O.N.</a></div></td>
            <td><span item="1240969@1" field="bidWithCurrencySymbol">151,8400&nbsp;€</span></td>
            <td><span item="1240969@1" field="askWithCurrencySymbol">151,8800&nbsp;€</span></td>
            <td><span item="1240969@1" field="midPerf1dWithCurrencySymbol"><span>+2,8600&nbsp;€</span></span></td>
            <td><span item="1240969@1" field="midTime">13:25:39</span></td>
        </tr></table>"""
        val expectedTime = LocalDateTime.of(2026, 8, 6, 13, 25, 39)
            .atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()

        val listing = LangSchwarzMarketDataClient().parseListings(
            html, LocalDate.of(2026, 8, 6), expectedTime + 1_000
        ).single()

        assertEquals("ENER6Y", listing.wkn)
        assertEquals("1240969", listing.itemId)
        assertEquals(151.86, listing.midpoint, 1e-9)
        assertEquals(149.0, listing.previousClose!!, 1e-9)
        assertEquals(expectedTime, listing.observedAtMillis)
    }

    @Test
    fun `rejects a quote whose time belongs to an earlier session`() {
        val html = """<table><tr>
            <td><div>ENER6Y</div></td><td><div><a href="/de/aktie/1240969">SIEMENS ENERGY</a></div></td>
            <td><span item="1240969@1" field="bidWithCurrencySymbol">151,8400 €</span></td>
            <td><span item="1240969@1" field="askWithCurrencySymbol">151,8800 €</span></td>
            <td><span item="1240969@1" field="midTime">13:25:39</span></td>
        </tr></table>"""
        val now = LocalDateTime.of(2026, 8, 6, 14, 0, 0)
            .atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()

        assertTrue(
            LangSchwarzMarketDataClient().parseListings(
                html, LocalDate.of(2026, 8, 6), now
            ).isEmpty()
        )
    }
}