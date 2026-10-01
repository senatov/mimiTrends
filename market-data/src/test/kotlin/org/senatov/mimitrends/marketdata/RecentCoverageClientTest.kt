package org.senatov.mimitrends.marketdata

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecentCoverageClientTest {
    private val client = RecentCoverageClient()

    @Test
    fun `Yahoo items retain publisher and publication time and reject unsafe links`() {
        val items = client.parseYahoo(
            """{"news":[
              {"title":"Apple shares fall","publisher":"Reuters","link":"https://example.org/a","providerPublishTime":1700000000},
              {"title":"Bad link","link":"javascript:alert(1)"}
            ]}"""
        )
        assertEquals(1, items.size)
        assertEquals("Reuters", items.single().publisher)
        assertEquals(1700000000, items.single().publishedAt?.epochSecond)
    }

    @Test
    fun `Benzinga feed filters by company and tolerates missing time`() {
        val items = client.parseBenzinga(
            """<rss><channel>
              <item><title>Apple faces new rumors</title><link>https://example.org/apple</link></item>
              <item><title>Oil market falls</title><link>https://example.org/oil</link></item>
            </channel></rss>""",
            "AAPL", "Apple"
        )
        assertEquals(1, items.size)
        assertEquals("Apple faces new rumors", items.single().title)
        assertNull(items.single().publishedAt)
    }

    @Test
    fun `company name search recognizes Zalando but ignores unrelated headlines`() {
        assertEquals("Zalando", client.searchName("Zalando SE"))
        assertTrue(client.matchesCompany("Zalando's ZEOS expands", "ZAL.DE", "Zalando SE"))
        assertFalse(client.matchesCompany("AI summit includes many companies", "ZAL.DE", "Zalando SE"))
    }

    @Test
    fun `wallstreetONLINE RSS accepts matching headlines and ISO publication time`() {
        val items = client.parseWallstreetOnline(
            """<rss><channel><item>
              <title><![CDATA[Zalando-Aktie fällt]]></title>
              <link>https://www.wallstreet-online.de/nachricht/123-zalando</link>
              <dc:date>2026-10-01T12:00:00+02:00</dc:date>
            </item></channel></rss>""",
            "ZAL.DE", "Zalando SE"
        )
        assertEquals(1, items.size)
        assertEquals("wallstreetONLINE", items.single().publisher)
        assertEquals("2026-10-01T10:00:00Z", items.single().publishedAt.toString())
    }

    @Test
    fun `wallstreetONLINE stock page retains company headlines and date`() {
        val items = client.parseWallstreetOnlineStockNews(
            """<h1>Nachrichten zu Zalando</h1><div class="tabpanes"><div class="tab"><table><tr>
              <td><div class="fw-semibold"><a href="/nachricht/123-zalando">Zalando analyst update</a></div>
              <div class="newsListSubtitle">30.09.26 · dpa-AFX Analysen</div></td>
            </tr></table></div></div>""",
            "ZAL.DE", "Zalando SE"
        )
        assertEquals(1, items.size)
        assertEquals("dpa-AFX Analysen", items.single().publisher)
        assertEquals("2026-09-30", items.single().publishedLabel)
    }
}
