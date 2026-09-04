package org.senatov.mimitrends

import org.senatov.mimitrends.model.MarketTimeZone
import java.time.Instant

internal object ChartRange {
    val values = listOf("1D", "5D", "1M", "3M", "6M", "1Y")

    fun normalize(value: String): String = value.takeIf(values::contains) ?: "3M"

    fun days(value: String): Long = when (value) {
        "1D" -> 1
        "5D" -> 5
        "1M" -> 30
        "6M" -> 180
        "1Y" -> 365
        else -> 90
    }

    fun fromEpochSeconds(value: String, symbol: String, now: Instant = Instant.now()): Long =
        if (value == "1D") {
            now.atZone(MarketTimeZone.forSymbol(symbol)).toLocalDate()
                .atStartOfDay(MarketTimeZone.forSymbol(symbol)).toEpochSecond()
        } else {
            now.minusSeconds(days(value) * 86_400L).epochSecond
        }
}
