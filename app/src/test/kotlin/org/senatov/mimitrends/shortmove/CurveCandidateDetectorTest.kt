package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.CurveDirection
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CurveCandidateDetectorTest {
    @Test
    fun `measures a rebound with stable anchor and bounded curve features`() {
        val now = 20_000L
        val bars = prices("PEP", now, 100.0, 99.8, 99.5, 99.7, 99.9, 100.3)

        val candidate = assertNotNull(CurveCandidateDetector.detect("PEP", bars, now))

        assertEquals(CurveDirection.RISE, candidate.direction)
        assertEquals(now - 3 * 60L, candidate.anchorEpochSeconds)
        assertTrue(candidate.changePercent > 0.8)
        assertTrue(candidate.volatilityPercent >= 0.0)
        assertTrue(candidate.pathEfficiency in 0.0..1.0)
        assertEquals(4, candidate.barCount)
    }

    @Test
    fun `measures a short drop outside the confirmed crash boundary`() {
        val now = 21_000L
        val bars = prices("DROP", now, 100.0, 100.0, 99.9, 99.8)

        val candidate = assertNotNull(CurveCandidateDetector.detect("DROP", bars, now))

        assertEquals(CurveDirection.DROP, candidate.direction)
        assertTrue(candidate.changePercent <= -0.20)
    }

    @Test
    fun `rejects stale or gapped curves`() {
        val now = 22_000L
        val stale = prices("STALE", now - 11 * 60L, 100.0, 99.8, 99.6)
        val gapped = listOf(
            bar("GAP", now - 10 * 60L, 100.0),
            bar("GAP", now - 5 * 60L, 99.7),
            bar("GAP", now, 99.5)
        )

        assertNull(CurveCandidateDetector.detect("STALE", stale, now))
        assertNull(CurveCandidateDetector.detect("GAP", gapped, now))
    }

    private fun prices(symbol: String, end: Long, vararg closes: Double) = closes.mapIndexed { index, close ->
        bar(symbol, end - (closes.lastIndex - index) * 60L, close)
    }

    private fun bar(symbol: String, time: Long, close: Double) =
        MinuteBar(symbol, time, close, close, close, close, 1_000.0)
}
