package org.senatov.mimitrends.charts

internal object TrendSeriesCalculator {
    fun ema(prices: List<Double>, period: Int): List<Double> {
        require(period > 0) { "EMA period must be positive" }
        if (prices.isEmpty()) return emptyList()
        val smoothing = 2.0 / (period + 1.0)
        var current = prices.first()
        return prices.mapIndexed { index, price ->
            if (index > 0) current = price * smoothing + current * (1.0 - smoothing)
            current
        }
    }

    fun linearRegression(prices: List<Double>): List<Double> {
        if (prices.size < 2) return prices
        val meanX = prices.lastIndex / 2.0
        val meanY = prices.average()
        val denominator = prices.indices.sumOf { index -> (index - meanX) * (index - meanX) }
        val slope = prices.indices.sumOf { index -> (index - meanX) * (prices[index] - meanY) } / denominator
        return prices.indices.map { index -> meanY + slope * (index - meanX) }
    }
}
