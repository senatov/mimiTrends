package org.senatov.mimitrends

internal object OpenMarketDataFreshness {
    fun isUsable(observedEpochSeconds: Long?, nowEpochSeconds: Long): Boolean {
        val observed = observedEpochSeconds ?: return false
        val age = nowEpochSeconds - observed
        return age >= -FUTURE_TOLERANCE_SECONDS && age <= MAX_AGE_SECONDS
    }

    const val MAX_AGE_SECONDS = 20 * 60L
    private const val FUTURE_TOLERANCE_SECONDS = 60L
}
