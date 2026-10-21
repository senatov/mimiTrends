package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.model.ProviderQuoteSnapshot
import kotlin.math.abs

internal object ShortMoveQuoteQuality {
    fun acceptable(quote: ProviderQuoteSnapshot, marketPriceEur: Double, quotePriceEur: (Double) -> Double): Boolean {
        val bid = quote.bid ?: return true
        val ask = quote.ask ?: return true
        if (bid <= 0.0 || ask < bid || marketPriceEur <= 0.0) return false
        val midpoint = (bid + ask) / 2.0
        if ((ask - bid) / midpoint * 100.0 > MAX_SPREAD_PERCENT) return false
        val converted = quotePriceEur(midpoint)
        return converted.isFinite() && abs(converted / marketPriceEur - 1.0) * 100.0 <= MAX_MARKET_DEVIATION_PERCENT
    }

    private const val MAX_SPREAD_PERCENT = 0.30
    private const val MAX_MARKET_DEVIATION_PERCENT = 2.0
}
