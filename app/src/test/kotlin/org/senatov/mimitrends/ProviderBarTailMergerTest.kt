package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.test.assertEquals

class ProviderBarTailMergerTest {
    @Test fun `appends a fresh Tradegate tail after Yahoo history`() {
        val yahoo = listOf(bar(60, 100.0), bar(120, 101.0))
        val providers = listOf(provider("TRADEGATE", 180, 102.0), provider("TRADEGATE", 240, 103.0))

        val merged = ProviderBarTailMerger.merge(yahoo, providers, "YAHOO", 300)

        assertEquals("TRADEGATE", merged.source)
        assertEquals(listOf(60L, 120L, 180L, 240L), merged.bars.map(MinuteBar::minuteEpochSeconds))
        assertEquals(VolumeStatus.MISSING, merged.bars.last().volumeStatus)
    }

    @Test fun `prefers Tradegate and never replaces overlapping Yahoo candles`() {
        val yahoo = listOf(bar(60, 100.0), bar(120, 101.0))
        val providers = listOf(
            provider("EURONEXT", 120, 900.0),
            provider("EURONEXT", 180, 104.0),
            provider("TRADEGATE", 180, 102.0)
        )

        val merged = ProviderBarTailMerger.merge(yahoo, providers, "YAHOO", 240)

        assertEquals(listOf(100.0, 101.0, 102.0), merged.bars.map(MinuteBar::close))
        assertEquals("TRADEGATE", merged.source)
    }

    @Test fun `ignores provider observations older than fifteen minutes`() {
        val yahoo = listOf(bar(60, 100.0))

        val merged = ProviderBarTailMerger.merge(
            yahoo, listOf(provider("TRADEGATE", 120, 101.0)), "YAHOO", 1_021
        )

        assertEquals("YAHOO", merged.source)
        assertEquals(yahoo, merged.bars)
    }

    private fun provider(provider: String, epoch: Long, price: Double) = ProviderMinuteBar(
        provider, "TEST.DE", "DE0000000001", "XGAT", "EUR", bar(epoch, price), epoch * 1_000
    )

    private fun bar(epoch: Long, price: Double) = MinuteBar(
        "TEST.DE", epoch, price, price, price, price, 0.0, VolumeStatus.MISSING
    )
}
