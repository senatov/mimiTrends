package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketDataSource
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.test.assertEquals

class ProviderBarTailMergerTest {
    @Test fun `appends a sufficiently dense Tradegate tail after Yahoo history`() {
        val yahoo = listOf(bar(60, 100.0), bar(120, 101.0))
        val providers = (3L..7L).map { provider("TRADEGATE", it * 60L, 99.0 + it) }

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 480)

        assertEquals(MarketDataSource.TRADEGATE, merged.latestSource)
        assertEquals(listOf(60L, 120L, 180L, 240L, 300L, 360L, 420L),
            merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(VolumeStatus.MISSING, merged.analysisBars.last().volumeStatus)
    }

    @Test fun `uses a lone live quote to extend current history for analysis`() {
        val yahoo = listOf(bar(60, 100.0), bar(120, 101.0))

        val merged = ProviderBarTailMerger.merge(
            yahoo, listOf(provider("TRADEGATE", 180, 102.0)), MarketDataSource.YAHOO, 240
        )

        assertEquals(listOf(60L, 120L, 180L), merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(180L, merged.latestEpochSeconds)
        assertEquals(180L, merged.latestAnalysisEpochSeconds)
        assertEquals(102.0, merged.latestObservation?.bar?.close)
    }

    @Test fun `live provider replaces overlapping Yahoo candle`() {
        val yahoo = listOf(bar(60, 100.0), bar(120, 101.0))
        val providers = listOf(
            provider("TRADEGATE", 120, 102.0),
            provider("TRADEGATE", 180, 102.0)
        )

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 240)

        assertEquals(listOf(100.0, 102.0, 102.0), merged.analysisBars.map(MinuteBar::close))
        assertEquals(MarketDataSource.TRADEGATE, merged.latestSource)
        assertEquals(102.0, merged.latestObservation?.bar?.close)
    }

    @Test fun `uses the freshest provider instead of a fixed provider priority`() {
        val yahoo = listOf(bar(60, 100.0))
        val providers = listOf(
            provider("EURONEXT", 120, 101.0),
            provider("TRADEGATE", 180, 102.0)
        )

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 240)

        assertEquals(MarketDataSource.TRADEGATE, merged.latestSource)
        assertEquals(listOf(60L, 180L), merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(102.0, merged.latestObservation?.bar?.close)
    }

    @Test fun `keeps the denser analytic tail while exposing the freshest quote`() {
        val yahoo = listOf(bar(60, 100.0))
        val providers = (2L..6L).map { provider("TRADEGATE", it * 60L, 99.0 + it) } +
            provider("LANG_SCHWARZ", 420, 106.0)

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 480)

        assertEquals(listOf(60L, 120L, 180L, 240L, 300L, 360L),
            merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(MarketDataSource.LANG_SCHWARZ, merged.latestSource)
        assertEquals(420L, merged.latestEpochSeconds)
        assertEquals(106.0, merged.latestObservation?.bar?.close)
        assertEquals(360L, merged.latestAnalysisEpochSeconds)
        assertEquals(true, merged.analysisTracksLatestQuote())
    }

    @Test fun `does not analyze a dense tail that ended long before the freshest quote`() {
        val yahoo = listOf(bar(60, 100.0))
        val providers = (2L..6L).map { provider("TRADEGATE", it * 60L, 99.0 + it) } +
            provider("LANG_SCHWARZ", 600, 106.0)

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 660)

        assertEquals(listOf(60L), merged.analysisBars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(MarketDataSource.LANG_SCHWARZ, merged.latestSource)
        assertEquals(false, merged.analysisTracksLatestQuote())
    }

    @Test fun `ignores provider observations older than fifteen minutes`() {
        val yahoo = listOf(bar(60, 100.0))

        val merged = ProviderBarTailMerger.merge(
            yahoo, listOf(provider("TRADEGATE", 120, 101.0)), MarketDataSource.YAHOO, 1_021
        )

        assertEquals(MarketDataSource.YAHOO, merged.latestSource)
        assertEquals(yahoo, merged.analysisBars)
    }

    @Test fun `older provider tail never rolls back newer primary data`() {
        val yahoo = listOf(bar(120, 101.0), bar(180, 102.0))
        val providers = listOf(provider("TRADEGATE", 120, 900.0))

        val merged = ProviderBarTailMerger.merge(yahoo, providers, MarketDataSource.YAHOO, 240)

        assertEquals(yahoo, merged.analysisBars)
        assertEquals(MarketDataSource.YAHOO, merged.latestSource)
        assertEquals(102.0, merged.analysisBars.last().close)
    }

    private fun provider(provider: String, epoch: Long, price: Double) = ProviderMinuteBar(
        provider, "TEST.DE", "DE0000000001", "XGAT", "EUR", bar(epoch, price), epoch * 1_000
    )

    private fun bar(epoch: Long, price: Double) = MinuteBar(
        "TEST.DE", epoch, price, price, price, price, 0.0, VolumeStatus.MISSING
    )
}
