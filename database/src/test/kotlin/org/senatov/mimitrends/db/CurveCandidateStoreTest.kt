package org.senatov.mimitrends.db

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.CurveCandidate
import org.senatov.mimitrends.model.CurveDecision
import org.senatov.mimitrends.model.CurveDirection
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurveCandidateStoreTest {
    @Test
    fun `persists approval and rejection for the same curve anchor across restarts`() {
        val path = Files.createTempDirectory("curve-candidates").resolve("market.db")
        val rise = candidate(CurveDirection.RISE)
        val drop = candidate(CurveDirection.DROP)
        CurveCandidateStore(path).use { store ->
            assertEquals(CurveDecision.PENDING, store.observe(rise))
            assertTrue(store.review(rise, approved = true))
            assertFalse(store.review(rise, approved = false))
            assertEquals(CurveDecision.PENDING, store.observe(drop))
            assertTrue(store.review(drop, approved = false))
        }
        CurveCandidateStore(path).use { store ->
            assertEquals(CurveDecision.APPROVED, store.observe(rise.copy(endEpochSeconds = 1_120L)))
            assertEquals(CurveDecision.REJECTED, store.observe(drop.copy(endEpochSeconds = 1_120L)))
            assertEquals(
                setOf(CurveDecision.APPROVED, CurveDecision.REJECTED),
                store.loadReviewed().map { it.second }.toSet()
            )
            assertEquals(
                CurveDecision.REJECTED, store.observe(
                    drop.copy(
                        anchorEpochSeconds = 1_060L, endEpochSeconds = 1_180L
                    )
                )
            )
            assertEquals(
                CurveDecision.PENDING, store.observe(
                    drop.copy(
                        anchorEpochSeconds = 2_000L, endEpochSeconds = 2_120L
                    )
                )
            )
        }
    }

    private fun candidate(direction: CurveDirection) = CurveCandidate(
        symbol = "PEP", direction = direction, anchorEpochSeconds = 1_000L, endEpochSeconds = 1_060L,
        startPrice = 100.0, endPrice = 101.0, changePercent = 1.0, durationMinutes = 1,
        volatilityPercent = 0.2, accelerationPercentPerMinute = 0.1, pathEfficiency = 0.8,
        reversalPercent = 0.1, reportedVolumeBars = 3, turnover = 300_000.0, barCount = 3
    )
}