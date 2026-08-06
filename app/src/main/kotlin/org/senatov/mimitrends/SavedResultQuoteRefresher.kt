package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ScanResult
import java.time.Instant

internal class SavedResultQuoteRefresher(private val repository: MarketRepository) {
    fun refresh(results: List<ScanResult>, nowEpochSeconds: Long = Instant.now().epochSecond): List<ScanResult> {
        val notBefore = nowEpochSeconds - MAX_STORED_QUOTE_AGE_SECONDS
        return results.map { result ->
            val observation = repository.loadLatestProviderMinuteBar(result.symbol, notBefore)
                ?: return@map result
            if (observation.observedAtMillis <= result.updatedAtMillis) return@map result
            result.copy(
                price = observation.bar.close,
                updatedAtMillis = observation.observedAtMillis,
                dataStatus = observation.provider
            )
        }
    }

    private companion object {
        const val MAX_STORED_QUOTE_AGE_SECONDS = 20 * 60L
    }
}
