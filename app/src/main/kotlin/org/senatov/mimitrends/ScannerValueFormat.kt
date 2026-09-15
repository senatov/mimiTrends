package org.senatov.mimitrends

import org.senatov.mimitrends.model.DisplayCurrency

internal object ScannerValueFormat {
    fun percent(value: Double?): String = value?.let { "%+.2f%%".format(it) } ?: "N/A"

    fun money(value: Double, currency: DisplayCurrency): String = when {
        value >= 1_000_000_000 -> "${currency.symbol}%.1fB".format(value / 1_000_000_000)
        value >= 1_000_000 -> "${currency.symbol}%.1fM".format(value / 1_000_000)
        value >= 1_000 -> "${currency.symbol}%.1fK".format(value / 1_000)
        else -> "${currency.symbol}%,.0f".format(value)
    }
}
