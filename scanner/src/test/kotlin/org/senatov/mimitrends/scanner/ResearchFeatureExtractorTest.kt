package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResearchFeatureExtractorTest {
    @Test fun `extracts point in time returns without future bars`() {
        val start = 1_768_226_400L
        val bars = (0..60).map { minute ->
            val price = 100.0 + minute * 0.1
            MinuteBar("TEST", start + minute * 60L, price, price + 0.05, price - 0.05, price, 1_000.0)
        }

        val features = assertNotNull(ResearchFeatureExtractor.extract(bars))

        assertEquals(bars.last().minuteEpochSeconds, features.observedEpochSeconds)
        assertEquals(106.0, features.entryPrice, 0.000_001)
        assertEquals((106.0 / 105.0 - 1.0) * 100.0, features.return10mPercent, 0.000_001)
        assertTrue(features.trendEfficiency10m > 0.99)
        assertTrue(features.vwapDistancePercent > 0.0)
    }

    @Test
    fun `does not relabel sparse bars as minute horizons`() {
        val start = 1_768_226_400L
        val bars = (0..12).filter { it != 2 }.map { minute ->
            val price = 100.0 + minute
            MinuteBar("TEST", start + minute * 60L, price, price, price, price, 1_000.0)
        }

        val features = assertNotNull(ResearchFeatureExtractor.extract(bars))

        assertTrue(features.return10mPercent.isNaN())
        assertEquals((112.0 / 111.0 - 1.0) * 100.0, features.return1mPercent, 0.000_001)
    }
}