package org.senatov.mimitrends.charts

import org.senatov.mimitrends.model.BrokerTrade
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult

internal data class TrendChartRenderRequest(
    val symbol: String,
    val companyName: String,
    val bars: List<MinuteBar>,
    val rangeLabel: String,
    val priceMultiplier: Double,
    val currencySymbol: String,
    val signal: ScanResult?,
    val trades: List<BrokerTrade>
)
