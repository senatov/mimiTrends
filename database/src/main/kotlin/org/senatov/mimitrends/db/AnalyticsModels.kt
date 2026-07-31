package org.senatov.mimitrends.db

data class InstrumentMetadata(
    val symbol: String,
    val name: String,
    val exchange: String,
    val currency: String,
    val timezone: String,
    val isin: String? = null,
    val wkn: String? = null,
    val aliases: String? = null,
    val tradable: Boolean = true,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class CorporateAction(
    val symbol: String,
    val actionType: String,
    val effectiveEpochSeconds: Long,
    val ratio: Double? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val source: String
)

data class AggregatedBar(
    val symbol: String,
    val resolutionMinutes: Int,
    val bucketEpochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class AnalyticsStats(
    val instruments: Long,
    val aggregateBars: Long,
    val scanRuns: Long,
    val scanCandidates: Long,
    val baselines: Long,
    val outcomes: Long
)
