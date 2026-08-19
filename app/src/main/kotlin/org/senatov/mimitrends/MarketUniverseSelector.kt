package org.senatov.mimitrends

import org.senatov.mimitrends.model.MarketRegion
import org.senatov.mimitrends.model.FinancialTransactionTaxExclusions
import org.senatov.mimitrends.model.ScannerCriteria

internal object MarketUniverseSelector {
    fun select(criteria: ScannerCriteria): List<String> = criteria.symbols.filterNot(
        FinancialTransactionTaxExclusions::contains
    ).filter { symbol ->
        val european = symbol.contains('.')
        when (criteria.marketRegion) {
            MarketRegion.BOTH -> true
            MarketRegion.US -> !european
            MarketRegion.EUROPE -> european
        }
    }
}
