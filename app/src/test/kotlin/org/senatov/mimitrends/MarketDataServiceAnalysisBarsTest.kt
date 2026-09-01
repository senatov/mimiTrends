package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MarketDataSource
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.test.assertEquals

class MarketDataServiceAnalysisBarsTest {
    @Test fun `keeps the current live Finnhub minute in analysis`() {
        val bars = listOf(bar(60), bar(120), bar(180))

        val analysis = currentAnalysisBars(bars, MarketDataSource.FINNHUB, 205)

        assertEquals(listOf(60L, 120L, 180L), analysis.map(MinuteBar::minuteEpochSeconds))
    }

    @Test fun `excludes the current incomplete Yahoo minute`() {
        val bars = listOf(bar(60), bar(120), bar(180))

        val analysis = currentAnalysisBars(bars, MarketDataSource.YAHOO, 205)

        assertEquals(listOf(60L, 120L), analysis.map(MinuteBar::minuteEpochSeconds))
    }

    private fun bar(epoch: Long) = MinuteBar(
        "TEST", epoch, 100.0, 101.0, 99.0, 100.0, 10.0, VolumeStatus.REPORTED
    )
}
