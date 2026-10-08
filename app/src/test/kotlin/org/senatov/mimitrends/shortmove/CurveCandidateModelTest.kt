package org.senatov.mimitrends.shortmove

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.CurveCandidate
import org.senatov.mimitrends.model.CurveDecision
import org.senatov.mimitrends.model.CurveDirection
import kotlin.test.assertEquals

class CurveCandidateModelTest {
    @Test
    fun `one rejection cannot automatically hide a different curve`() {
        val rejected = example("A", 1_000L, CurveDirection.DROP, 0.8)
        val model = CurveCandidateModel(listOf(rejected to CurveDecision.REJECTED))

        assertEquals(CurveAssessment.REVIEW, model.assess(example("B", 2_000L, CurveDirection.DROP, 0.8)))
    }

    @Test
    fun `several close rejections filter a new candidate without approvals`() {
        val labels = (1..3).map { index ->
            example("R$index", index * 1_000L, CurveDirection.DROP, 0.3) to CurveDecision.REJECTED
        }

        assertEquals(
            CurveAssessment.HIDE, CurveCandidateModel(labels).assess(
                example("NEW", 10_000L, CurveDirection.DROP, 0.3)
            )
        )
    }

    @Test
    fun `several similar rejections hide a curve and approvals select another shape`() {
        val rejected = (1..3).map { index ->
            example("R$index", index * 1_000L, CurveDirection.RISE, 0.2) to CurveDecision.REJECTED
        }
        val approved = (1..3).map { index ->
            example("A$index", index * 2_000L, CurveDirection.RISE, 0.9) to CurveDecision.APPROVED
        }
        val model = CurveCandidateModel(rejected + approved)

        assertEquals(CurveAssessment.HIDE, model.assess(example("NEW", 10_000L, CurveDirection.RISE, 0.2)))
        assertEquals(CurveAssessment.SHOW, model.assess(example("NEW", 10_000L, CurveDirection.RISE, 0.9)))
        assertEquals(CurveAssessment.REVIEW, model.assess(example("NEW", 10_000L, CurveDirection.DROP, 0.2)))
    }

    @Test
    fun `conflicting nearby labels require a human review`() {
        val shape = example("A", 1_000L, CurveDirection.RISE, 0.6)
        val labels = listOf(
            shape to CurveDecision.APPROVED,
            shape.copy(symbol = "B", anchorEpochSeconds = 2_000L) to CurveDecision.APPROVED,
            shape.copy(symbol = "C", anchorEpochSeconds = 3_000L) to CurveDecision.APPROVED,
            shape.copy(symbol = "D", anchorEpochSeconds = 4_000L) to CurveDecision.REJECTED
        )

        assertEquals(
            CurveAssessment.REVIEW, CurveCandidateModel(labels).assess(
                shape.copy(symbol = "NEW", anchorEpochSeconds = 5_000L)
            )
        )
    }

    private fun example(symbol: String, anchor: Long, direction: CurveDirection, efficiency: Double) =
        CurveCandidate(
            symbol, direction, anchor, anchor + 240L, 100.0, 101.0, 1.0, 4,
            0.15, 0.10, efficiency, 0.05, 4, 400_000.0, 5
        )
}