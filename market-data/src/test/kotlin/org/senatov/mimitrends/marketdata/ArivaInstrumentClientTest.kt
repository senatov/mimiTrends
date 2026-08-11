package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArivaInstrumentClientTest {
    private val client = ArivaInstrumentClient()

    @Test fun `parses identifiers and canonical page from instrument metadata`() {
        val reference = client.parseReference("""
            <html><head>
            <meta name="description" content="WKN 716460 | ISIN DE0007164600 | SAP Aktie mit aktuellem Kurs">
            <link rel="canonical" href="https://www.ariva.de/aktien/sap-se-aktie">
            </head></html>
        """.trimIndent(), "https://www.ariva.de/search/search.m")

        assertEquals("DE0007164600", reference.isin)
        assertEquals("716460", reference.wkn)
        assertEquals("https://www.ariva.de/aktien/sap-se-aktie", reference.pageUrl)
    }

    @Test fun `rejects a page without structured instrument identifiers`() {
        assertFailsWith<ProviderDataUnavailableException> {
            client.parseReference("<html><head></head></html>", "https://www.ariva.de/")
        }
    }
}
