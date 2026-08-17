package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.VolumeStatus
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GapContinuationDetectorTest {
    private val detector = GapContinuationDetector(ZoneId.of("UTC"))

    @Test fun `detects an opening gap that continues near the session high`() {
        val bars = previousSession() + currentSession(start = 102.0, step = 0.08)

        val result = requireNotNull(detector.detect("TEST", bars, ScannerCriteria()))

        assertEquals("Gap-and-go ↑", result.signalSource)
        assertTrue(result.priceAnomaly >= 2.0)
        assertTrue(result.windowChangePercent > result.priceAnomaly)
    }

    @Test fun `rejects a gap that fades after the open`() {
        val bars = previousSession() + currentSession(start = 102.0, step = -0.12)

        assertNull(detector.detect("TEST", bars, ScannerCriteria()))
    }

    private fun previousSession(): List<MinuteBar> = (0 until 20).map { index ->
        bar(1_800_000_000L + index * 60L, 100.0, 100.0)
    }

    private fun currentSession(start: Double, step: Double): List<MinuteBar> = (0 until 20).map { index ->
        val open = start + step * index
        bar(1_800_086_400L + index * 60L, open, open + step)
    }

    private fun bar(epoch: Long, open: Double, close: Double) = MinuteBar(
        "TEST", epoch, open, maxOf(open, close) + 0.02, minOf(open, close) - 0.02, close,
        10_000.0, VolumeStatus.REPORTED
    )
}
