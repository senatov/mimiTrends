package org.senatov.mimitrends

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
}
