package org.senatov.mimitrends.marketdata

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import org.senatov.mimitrends.model.VolumeStatus

class YahooFinanceClientTest {
    @Test fun `classifies an empty quote window for retry`() {
        val json = """{"chart":{"result":[{"meta":{},"timestamp":[1785840000],"indicators":{"quote":[{"open":[null],"high":[null],"low":[null],"close":[null],"volume":[null]}]}}]}}"""

        assertFailsWith<YahooEmptyOhlcvException> { YahooFinanceClient().parse("KNEBV.HE", json) }
    }

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
        assertEquals(VolumeStatus.REPORTED, series.bars.last().volumeStatus)
    }

    @Test fun `distinguishes missing and reported zero volume`() {
        val json = """{"chart":{"result":[{"meta":{},"timestamp":[60,120],
            "indicators":{"quote":[{"open":[10.0,10.0],"high":[10.1,10.1],"low":[9.9,9.9],
            "close":[10.0,10.0],"volume":[null,0]}]}}],"error":null}}"""

        val bars = YahooFinanceClient().parse("TEST", json).bars

        assertEquals(VolumeStatus.MISSING, bars[0].volumeStatus)
        assertEquals(VolumeStatus.ZERO, bars[1].volumeStatus)
    }

    @Test fun `parses split and dividend events`() {
        val json = """{"chart":{"result":[{"meta":{"currency":"USD","longName":"Example","exchangeName":"NYSE"},
            "timestamp":[60],"events":{"splits":{"100":{"date":100,"numerator":2.0,"denominator":1.0}},
            "dividends":{"120":{"date":120,"amount":0.25}}},"indicators":{"quote":[{"open":[10.0],
            "high":[10.2],"low":[9.9],"close":[10.1],"volume":[100]}]}}],"error":null}}"""
        val events = YahooFinanceClient().parse("TEST", json).events
        assertEquals(2.0, events.first { it.type == "SPLIT" }.ratio)
        assertEquals(0.25, events.first { it.type == "DIVIDEND" }.amount)
    }

    @Test fun `keeps supported US equities from the day gainers screener`() {
        val json = """{"finance":{"result":[{"quotes":[
            {"symbol":"LITE","regularMarketPrice":100.0,"regularMarketChangePercent":5.2,"quoteType":"EQUITY","exchange":"NMS"},
            {"symbol":"SAP.DE","regularMarketPrice":200.0,"regularMarketChangePercent":6.0,"quoteType":"EQUITY","exchange":"GER"},
            {"symbol":"SPY","regularMarketPrice":500.0,"regularMarketChangePercent":1.0,"quoteType":"ETF","exchange":"PCX"},
            {"symbol":"AMD","regularMarketPrice":150.0,"regularMarketChangePercent":4.1,"quoteType":"EQUITY","exchange":"NMS"}
        ]}]}}"""

        val leaders = YahooFinanceClient().parseDayGainers(json)

        assertEquals(listOf("LITE", "AMD"), leaders.map(YahooMarketLeader::symbol))
    }
}
