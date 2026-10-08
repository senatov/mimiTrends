package org.senatov.mimitrends.model

enum class CurveDirection {
    RISE,
    DROP
}

enum class CurveDecision {
    PENDING,
    APPROVED,
    REJECTED,
    MODEL_APPROVED
}

data class CurveCandidate(
    val symbol: String,
    val direction: CurveDirection,
    val anchorEpochSeconds: Long,
    val endEpochSeconds: Long,
    val startPrice: Double,
    val endPrice: Double,
    val changePercent: Double,
    val durationMinutes: Int,
    val volatilityPercent: Double,
    val accelerationPercentPerMinute: Double,
    val pathEfficiency: Double,
    val reversalPercent: Double,
    val reportedVolumeBars: Int,
    val turnover: Double,
    val barCount: Int
)