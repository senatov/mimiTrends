package org.senatov.mimitrends.model

/** Point-in-time features stored before a future outcome is known. */
data class ResearchFeatures(
    val observedEpochSeconds: Long,
    val entryPrice: Double,
    val return1mPercent: Double,
    val return3mPercent: Double,
    val return5mPercent: Double,
    val return10mPercent: Double,
    val return30mPercent: Double,
    val return60mPercent: Double,
    val range10mPercent: Double,
    val realizedVolatility30m: Double,
    val vwapDistancePercent: Double,
    val sessionHighDistancePercent: Double,
    val sessionLowDistancePercent: Double,
    val volumeRatio10m: Double,
    val trendEfficiency10m: Double
)
