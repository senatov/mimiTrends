package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.ScanResult
import java.time.Instant

internal class SavedResultQuoteRefresher(private val repository: MarketRepository) {
    fun refresh(results: List<ScanResult>, nowEpochSeconds: Long = Instant.now().epochSecond): List<ScanResult> {
        val notBeforeMillis = (nowEpochSeconds - MAX_STORED_QUOTE_AGE_SECONDS) * 1_000L
        return results.map { result ->
            val quote = repository.loadLatestProviderQuote(result.symbol, notBeforeMillis) ?: return@map result
            if (quote.observedAtMillis <= result.updatedAtMillis) return@map result
            result.copy(
                price = quote.last,
                bidPrice = quote.bid ?: result.bidPrice,
                askPrice = quote.ask ?: result.askPrice,
                executableQuoteAtMillis = quote.observedAtMillis,
                updatedAtMillis = quote.observedAtMillis,
                dataStatus = quote.provider
            )
        }
    }

    private companion object {
        const val MAX_STORED_QUOTE_AGE_SECONDS = 20 * 60L
    }
}
