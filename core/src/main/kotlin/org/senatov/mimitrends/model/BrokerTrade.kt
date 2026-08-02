package org.senatov.mimitrends.model

data class BrokerTrade(
    val symbol: String,
    val isin: String?,
    val quantity: Double,
    val entryEpochSeconds: Long,
    val entryPrice: Double,
    val exitEpochSeconds: Long?,
    val exitPrice: Double?,
    val profitAmount: Double?,
    val profitPercent: Double?,
    val feesAndTaxes: Double,
    val currency: String
) {
    val isOpen: Boolean get() = exitEpochSeconds == null
}
