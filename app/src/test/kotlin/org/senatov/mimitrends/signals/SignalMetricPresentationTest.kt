package org.senatov.mimitrends.signals

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalMetricPresentationTest {
    @Test
    fun `entry quality presents a separate pullback decision`() {
        val metric = SignalMetricPresentation.entryQuality(
            TestScanResult.create().copy(
                entryQualityScore = 31,
                entryQualityConfidence = 86,
                entryQualityLabel = "Wait for pullback",
                entryCooldownMinutes = 5,
                entryQualityDetails = "cooldown 5m"
            )
        )

        assertEquals("31% · Wait", metric.label)
        assertTrue(metric.details.contains("Wait for pullback"))
    }

    @Test
    fun `high anomaly is not presented as a trading recommendation`() {
        val metric = SignalMetricPresentation.strength(TestScanResult.create(anomalyScore = 4.2))

        assertEquals("4.2", metric.label)
        assertTrue(metric.details.contains("not a buy/sell recommendation"))
        assertTrue(metric.details.contains("does not predict direction"))
    }

    @Test
    fun `labels missing impulse volume as unavailable`() {
        val result = TestScanResult.create(signalSource = "Impulse ↓")

        assertEquals("—", SignalMetricPresentation.volume(result).label)
    }

    @Test
    fun `shows a dash for a trend without volume metrics`() {
        val result = TestScanResult.create(signalSource = "Trend ↑")

        assertEquals("—", SignalMetricPresentation.volume(result).label)
    }

    @Test
    fun `labels early momentum without reliable volume as unavailable`() {
        val result = TestScanResult.create(signalSource = "Momentum 3m ↑")

        assertEquals("—", SignalMetricPresentation.volume(result).label)
    }

    @Test
    fun `describes a statistically rare candle without promising strength`() {
        val result = TestScanResult.create(anomalyScore = 2.8)

        assertEquals("↑↑", SignalMetricPresentation.priceAction(result).label)
    }

    @Test
    fun `presents an extended rise as a wait state`() {
        val result = TestScanResult.create(signalSource = "Steady rise ↑ · extended · wait for pullback")

        val metric = SignalMetricPresentation.priceAction(result)

        assertEquals("↑↑", metric.label)
        assertTrue(metric.details.contains("not a buy signal"))
    }
}