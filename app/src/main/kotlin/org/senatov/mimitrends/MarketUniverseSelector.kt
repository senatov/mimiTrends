package org.senatov.mimitrends

import org.senatov.mimitrends.model.MarketRegion
import org.senatov.mimitrends.model.FinancialTransactionTaxExclusions
import org.senatov.mimitrends.model.ScannerCriteria

internal object MarketUniverseSelector {
    fun select(criteria: ScannerCriteria): List<String> = criteria.symbols.filterNot(
        FinancialTransactionTaxExclusions::contains
    ).filter { symbol -> includes(symbol, criteria.marketRegion) }

    fun includes(symbol: String, region: MarketRegion): Boolean {
        val european = symbol.contains('.')
        return when (region) {
            MarketRegion.BOTH -> true
            MarketRegion.US -> !european
            MarketRegion.EUROPE -> european
        }
    }
}
