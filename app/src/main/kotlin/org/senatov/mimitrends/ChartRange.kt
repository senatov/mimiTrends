package org.senatov.mimitrends

internal object ChartRange {
    fun days(value: String): Long = when (value) {
        "1D" -> 1
        "5D" -> 5
        "1M" -> 30
        "6M" -> 180
        "1Y" -> 365
        else -> 90
    }
}
