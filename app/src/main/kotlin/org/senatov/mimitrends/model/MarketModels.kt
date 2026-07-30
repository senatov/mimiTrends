package org.senatov.mimitrends.model

data class Candle(
    val timestampSeconds: Long,
    val close: Double
)

data class Quote(
    val current: Double,
    val change: Double,
    val percentChange: Double,
    val high: Double,
    val low: Double,
    val open: Double,
    val previousClose: Double
)

data class MarketSnapshot(
    val symbol: String,
    val quote: Quote,
    val candles: List<Candle>
)
