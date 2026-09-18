package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

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
