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
    val outcomes: Long,
    val brokerTransactions: Long,
    val linkedBrokerTransactions: Long
)

data class WalkForwardResearchReport(
    val horizonMinutes: Int,
    val frictionPercent: Double,
    val outcomeSamples: Int,
    val evaluatedSamples: Int,
    val metrics: List<WalkForwardMetric>
)

data class WalkForwardMetric(
    val family: String,
    val direction: Int,
    val samples: Int,
    val distinctDays: Int,
    val predictedWinRate: Double,
    val actualWinRate: Double,
    val brierScore: Double,
    val averageNetReturnPercent: Double
)

data class ResearchBackfillOutcome(
    val horizonMinutes: Int,
    val observedPrice: Double,
    val returnPercent: Double,
    val elapsedMinutes: Double,
    val maximumReturnPercent: Double,
    val minimumReturnPercent: Double,
    val observedEpochSeconds: Long
)

data class ResearchBackfillSample(
    val result: org.senatov.mimitrends.model.ScanResult?,
    val features: org.senatov.mimitrends.model.ResearchFeatures,
    val outcomes: List<ResearchBackfillOutcome>
)

data class PredictiveTrainingResult(
    val horizonMinutes: Int,
    val status: String,
    val trainingSamples: Int,
    val validationSamples: Int,
    val modelBrier: Double,
    val baselineBrier: Double,
    val reason: String? = null
)

data class BrokerTransaction(
    val source: String,
    val reference: String?,
    val fingerprint: String,
    val occurredAtEpochSeconds: Long,
    val status: String,
    val description: String,
    val assetType: String,
    val type: String,
    val isin: String?,
    val shares: Double,
    val price: Double,
    val amount: Double,
    val fee: Double,
    val tax: Double,
    val currency: String
)

data class BrokerImportResult(
    val parsed: Int,
    val imported: Int,
    val duplicates: Int,
    val linkedToSignals: Int,
    val closedPositions: Int,
    val openPositions: Int,
    val correctedOrder: Int,
    val unmatchedSells: Int
)
