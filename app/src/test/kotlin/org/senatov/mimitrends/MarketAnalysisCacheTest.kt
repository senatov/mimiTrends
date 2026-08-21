package org.senatov.mimitrends

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.VolumeStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketAnalysisCacheTest {
    @Test fun `reuses tiny same-minute changes but invalidates a new minute`() {
        val cache = MarketAnalysisCache()
        val criteria = ScannerCriteria()
        val evaluation = ScanEvaluation(TestScanResult.create(), emptyList(), null)
        cache.record("TEST", listOf(bar(60, 100.0, 10_000.0)), criteria, evaluation)

        val reused = cache.reuse("TEST", listOf(bar(60, 100.05, 10_100.0)), criteria, 120_000L)

        assertNotNull(reused)
        assertTrue(reused.reusedAnalysis)
        assertNull(cache.reuse("TEST", listOf(bar(120, 100.05, 10_100.0)), criteria, 180_000L))
    }

    @Test fun `invalidates material price changes within a minute`() {
        val cache = MarketAnalysisCache()
        val criteria = ScannerCriteria()
        cache.record("TEST", listOf(bar(60, 100.0, 10_000.0)), criteria,
            ScanEvaluation(TestScanResult.create(), emptyList(), null))

        assertFalse(cache.reuse("TEST", listOf(bar(60, 100.2, 10_000.0)), criteria, 120_000L) != null)
    }

    private fun bar(epoch: Long, close: Double, volume: Double) = MinuteBar(
        "TEST", epoch, close, close, close, close, volume, VolumeStatus.REPORTED
    )
}
