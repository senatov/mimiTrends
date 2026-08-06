package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketDataSource
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.test.assertEquals

class ProviderBarTailMergerTest {
    @Test fun `appends a fresh Tradegate tail after Yahoo history`() {
        val yahoo = listOf(bar(60, 100.0), bar(120, 101.0))
        val providers = listOf(provider("TRADEGATE", 180, 102.0), provider("TRADEGATE", 240, 103.0))

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 300)

        assertEquals(MarketDataSource.TRADEGATE, merged.latestSource)
        assertEquals(listOf(60L, 120L, 180L, 240L), merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(VolumeStatus.MISSING, merged.analysisBars.last().volumeStatus)
    }

    @Test fun `prefers Tradegate and never replaces overlapping Yahoo candles`() {
        val yahoo = listOf(bar(60, 100.0), bar(120, 101.0))
        val providers = listOf(
            provider("EURONEXT", 120, 900.0),
            provider("EURONEXT", 180, 104.0),
            provider("TRADEGATE", 180, 102.0)
        )

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 240)

        assertEquals(listOf(100.0, 101.0, 102.0), merged.analysisBars.map(MinuteBar::close))
        assertEquals(MarketDataSource.TRADEGATE, merged.latestSource)
    }

    @Test fun `uses the freshest provider instead of a fixed provider priority`() {
        val yahoo = listOf(bar(60, 100.0))
        val providers = listOf(
            provider("BOERSE_DE", 120, 101.0),
            provider("TRADEGATE", 180, 102.0)
        )

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 240)

        assertEquals(MarketDataSource.TRADEGATE, merged.latestSource)
        assertEquals(102.0, merged.analysisBars.last().close)
    }

    @Test fun `keeps the denser analytic tail while exposing the freshest quote`() {
        val yahoo = listOf(bar(60, 100.0))
        val providers = listOf(
            provider("TRADEGATE", 120, 101.0),
            provider("TRADEGATE", 180, 102.0),
            provider("TRADEGATE", 240, 103.0),
            provider("BNP_PARIBAS", 300, 104.0)
        )

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 360)

        assertEquals(listOf(60L, 120L, 180L, 240L), merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(MarketDataSource.BNP_PARIBAS, merged.latestSource)
        assertEquals(300L, merged.latestEpochSeconds)
        assertEquals(104.0, merged.latestObservation?.bar?.close)
    }

    @Test fun `does not analyze a dense tail that ended long before the freshest quote`() {
        val yahoo = listOf(bar(60, 100.0))
        val providers = listOf(
            provider("TRADEGATE", 120, 101.0),
            provider("TRADEGATE", 180, 102.0),
            provider("BNP_PARIBAS", 600, 104.0)
        )

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 660)

        assertEquals(listOf(60L, 600L), merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(MarketDataSource.BNP_PARIBAS, merged.latestSource)
    }

    @Test fun `ignores provider observations older than fifteen minutes`() {
        val yahoo = listOf(bar(60, 100.0))

        val merged = ProviderBarTailMerger.merge(
            yahoo, listOf(provider("TRADEGATE", 120, 101.0)), MarketDataSource.YAHOO, 1_021
        )

        assertEquals(MarketDataSource.YAHOO, merged.latestSource)
        assertEquals(yahoo, merged.analysisBars)
    }

    private fun provider(provider: String, epoch: Long, price: Double) = ProviderMinuteBar(
        provider, "TEST.DE", "DE0000000001", "XGAT", "EUR", bar(epoch, price), epoch * 1_000
    )

    private fun bar(epoch: Long, price: Double) = MinuteBar(
        "TEST.DE", epoch, price, price, price, price, 0.0, VolumeStatus.MISSING
    )
}
