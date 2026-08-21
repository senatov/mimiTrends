package org.senatov.mimitrends

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria

internal class MarketAnalysisCache {
    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun reuse(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria, nowMillis: Long): ScanEvaluation? {
        val entry = entries[symbol] ?: return null
        if (!entry.fingerprint.matches(fingerprint(bars, criteria))) return null
        return entry.evaluation.withEvaluationTime(nowMillis).copy(reusedAnalysis = true)
    }

    @Synchronized
    fun record(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria, evaluation: ScanEvaluation) {
        entries[symbol] = Entry(fingerprint(bars, criteria), evaluation.copy(reusedAnalysis = false))
    }

    private fun fingerprint(bars: List<MinuteBar>, criteria: ScannerCriteria): Fingerprint {
        val latest = bars.lastOrNull()
        return Fingerprint(latest?.minuteEpochSeconds ?: 0L, latest?.close ?: Double.NaN, latest?.volume ?: Double.NaN,
            bars.size, criteria.hashCode())
    }

    private fun ScanEvaluation.withEvaluationTime(nowMillis: Long): ScanEvaluation = copy(
        primary = primary?.withEvaluationTime(nowMillis),
        fallback = fallback.map { it?.withEvaluationTime(nowMillis) },
        longTerm = longTerm?.withEvaluationTime(nowMillis),
        context = context?.withEvaluationTime(nowMillis)
    )

    private fun ScanResult.withEvaluationTime(nowMillis: Long) = copy(scanEvaluatedAtMillis = nowMillis)

    private data class Entry(val fingerprint: Fingerprint, val evaluation: ScanEvaluation)
    private data class Fingerprint(
        val latestMinute: Long,
        val price: Double,
        val volume: Double,
        val barCount: Int,
        val criteriaHash: Int
    ) {
        fun matches(other: Fingerprint): Boolean {
            if (latestMinute != other.latestMinute || barCount != other.barCount || criteriaHash != other.criteriaHash) {
                return false
            }
            val priceChange = if (price > 0.0 && other.price.isFinite()) kotlin.math.abs(other.price / price - 1.0) else 1.0
            val volumeChange = if (volume > 0.0 && other.volume.isFinite()) {
                kotlin.math.abs(other.volume / volume - 1.0)
            } else if (volume == other.volume) 0.0 else 1.0
            return priceChange < PRICE_EPSILON && volumeChange < VOLUME_EPSILON
        }
    }

    private companion object {
        // The completed-minute timestamp is the hard boundary; within it, tiny quote noise is ignored.
        const val PRICE_EPSILON = 0.001
        const val VOLUME_EPSILON = 0.02
    }
}
