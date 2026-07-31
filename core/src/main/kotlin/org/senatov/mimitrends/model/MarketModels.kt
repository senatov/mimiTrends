package org.senatov.mimitrends.model

data class TradeTick(
    val symbol: String,
    val price: Double,
    val timestampMillis: Long,
    val volume: Double
)

data class MinuteBar(
    val symbol: String,
    val minuteEpochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

enum class DisplayCurrency(val symbol: String) { EUR("€"), USD("$") }

data class ScannerCriteria(
    val minRelativeVolume: Double = 3.0,
    val minChange1mPercent: Double = 1.0,
    val minChange5mPercent: Double = 1.2,
    val minPrice: Double = 8.0,
    val minSessionVolume: Double = 500_000.0,
    val baselineSessions: Int = 20,
    val batchSize: Int = 50,
    val rotationSeconds: Long = 30,
    val displayCurrency: DisplayCurrency = DisplayCurrency.EUR,
    val symbols: List<String> = listOf("AAPL", "AMD", "AMZN", "META", "MSFT", "NVDA", "TSLA")
)

data class ScanResult(
    val symbol: String,
    val price: Double,
    val relativeVolume: Double?,
    val change1mPercent: Double?,
    val change5mPercent: Double?,
    val sessionVolume: Double,
    val matches: Boolean,
    val updatedAtMillis: Long
)
