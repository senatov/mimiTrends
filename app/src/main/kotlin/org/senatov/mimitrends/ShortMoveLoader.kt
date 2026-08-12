package org.senatov.mimitrends

import org.senatov.mimitrends.db.MarketRepository

internal class ShortMoveLoader(private val repository: MarketRepository) {
    fun load(symbols: Collection<String>, nowEpochSeconds: Long = java.time.Instant.now().epochSecond): List<ShortMove> {
        val from = nowEpochSeconds - 15 * 60
        return ShortMoveDetector.rank(symbols.associateWith { repository.loadMinuteBars(it, from) }, nowEpochSeconds)
    }
}
