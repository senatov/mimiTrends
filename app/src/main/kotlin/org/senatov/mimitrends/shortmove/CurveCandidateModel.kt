package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.model.CurveCandidate
import org.senatov.mimitrends.model.CurveDecision
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

internal enum class CurveAssessment {
    REVIEW,
    SHOW,
    HIDE
}

internal class CurveCandidateModel(private val loadReviewed: () -> List<Pair<CurveCandidate, CurveDecision>>) {
    constructor(initial: List<Pair<CurveCandidate, CurveDecision>>) : this({ initial })

    @Volatile
    private var examples: List<Pair<CurveCandidate, CurveDecision>>? = null

    @Synchronized
    private fun reviewed(): List<Pair<CurveCandidate, CurveDecision>> {
        if (examples == null) examples = loadReviewed().take(MAX_EXAMPLES)
        return examples.orEmpty()
    }

    @Synchronized
    fun record(candidate: CurveCandidate, approved: Boolean) {
        val decision = if (approved) CurveDecision.APPROVED else CurveDecision.REJECTED
        examples = (listOf(candidate to decision) + reviewed().filterNot { (old, _) ->
            old.symbol == candidate.symbol && old.direction == candidate.direction &&
                    old.anchorEpochSeconds == candidate.anchorEpochSeconds
        }).take(MAX_EXAMPLES)
    }

    fun assess(candidate: CurveCandidate): CurveAssessment {
        val neighbors = reviewed().asSequence()
            .filter { (example, _) -> example.direction == candidate.direction }
            .map { (example, decision) -> Neighbor(example, decision, distance(candidate, example)) }
            .sortedBy(Neighbor::distance)
            .take(NEIGHBOR_SEARCH_LIMIT)
            .toList()
        val approved = neighbors.filter { it.decision == CurveDecision.APPROVED }
        val rejected = neighbors.filter { it.decision == CurveDecision.REJECTED }
        val good = strongMatch(approved, rejected)
        val bad = strongMatch(rejected, approved)
        return when {
            good && !bad -> CurveAssessment.SHOW
            bad && !good -> CurveAssessment.HIDE
            else -> CurveAssessment.REVIEW
        }
    }

    private fun strongMatch(same: List<Neighbor>, opposite: List<Neighbor>): Boolean {
        val closest = same.take(REQUIRED_NEIGHBORS)
        if (closest.size < REQUIRED_NEIGHBORS || closest.map { it.candidate.symbol }.distinct().size < 2) return false
        val strictThreshold = if (opposite.isEmpty()) MAX_MATCH_DISTANCE * 0.7 else MAX_MATCH_DISTANCE
        if (closest.last().distance > strictThreshold) return false
        val average = closest.map(Neighbor::distance).average()
        if (average > strictThreshold * 0.75) return false
        val opposingDistance = opposite.firstOrNull()?.distance ?: Double.POSITIVE_INFINITY
        return opposingDistance > closest.last().distance * MIN_CLASS_MARGIN &&
                opposingDistance > average + MIN_ABSOLUTE_MARGIN
    }

    private fun distance(first: CurveCandidate, second: CurveCandidate): Double {
        val features = doubleArrayOf(
            logRatio(abs(first.changePercent), abs(second.changePercent), 0.05, 1.5),
            logRatio(first.durationMinutes.toDouble(), second.durationMinutes.toDouble(), 1.0, 2.0),
            logRatio(first.volatilityPercent, second.volatilityPercent, 0.05, 2.0),
            (first.accelerationPercentPerMinute - second.accelerationPercentPerMinute) / 0.15,
            (first.pathEfficiency - second.pathEfficiency) / 0.25,
            (first.reversalPercent - second.reversalPercent) / 0.30,
            (volumeFraction(first) - volumeFraction(second)) / 0.33,
            logRatio(first.turnover, second.turnover, 1_000.0, 4.0),
            (barDensity(first) - barDensity(second)) / 0.35
        )
        val weights = doubleArrayOf(2.0, 1.0, 1.2, 1.0, 1.2, 1.0, 1.1, 0.6, 0.7)
        return sqrt(features.indices.sumOf { index ->
            weights[index] * features[index] * features[index]
        } / weights.sum())
    }

    private fun logRatio(first: Double, second: Double, floor: Double, scale: Double): Double =
        ln((first + floor) / (second + floor)) / ln(scale)

    private fun volumeFraction(candidate: CurveCandidate): Double =
        candidate.reportedVolumeBars.toDouble() / candidate.barCount.coerceAtLeast(1)

    private fun barDensity(candidate: CurveCandidate): Double =
        candidate.barCount.toDouble() / (candidate.durationMinutes + 1).coerceAtLeast(1)

    private data class Neighbor(
        val candidate: CurveCandidate,
        val decision: CurveDecision,
        val distance: Double
    )

    private companion object {
        const val MAX_EXAMPLES = 3_000
        const val NEIGHBOR_SEARCH_LIMIT = 20
        const val REQUIRED_NEIGHBORS = 3
        const val MAX_MATCH_DISTANCE = 0.50
        const val MIN_CLASS_MARGIN = 1.5
        const val MIN_ABSOLUTE_MARGIN = 0.18
    }
}
