package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class YahooFinanceClientTest {
    @Test fun `parses Yahoo chart OHLCV and metadata`() {
        val json = """{"chart":{"result":[{"meta":{"currency":"EUR","longName":"SAP SE","fullExchangeName":"XETRA"},
            "timestamp":[60,120],"indicators":{"quote":[{"open":[100.0,101.0],"high":[102.0,103.0],
            "low":[99.0,100.0],"close":[101.0,102.0],"volume":[500,750]}]}}],"error":null}}"""
        val series = YahooFinanceClient().parse("SAP.DE", json)
        assertEquals("SAP SE", series.companyName)
        assertEquals("XETRA", series.exchange)
        assertEquals("EUR", series.currency)
        assertEquals(2, series.bars.size)
        assertEquals(102.0, series.bars.last().close)
        assertEquals(750.0, series.bars.last().volume)
    }
}
