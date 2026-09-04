package org.senatov.mimitrends

import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.BrokerTrade
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.MinuteBar

internal class ChartDataLoader(
    private val repository: MarketRepository,
    private val analytics: AnalyticsRepository,
    private val exchangeRates: ExchangeRateService
) {
    fun load(symbol: String, range: String, targetCurrency: DisplayCurrency): ChartData {
        val bars = repository.loadMinuteBars(symbol, ChartRange.fromEpochSeconds(range, symbol))
            .map { exchangeRates.convertBar(symbol, it, targetCurrency) }
        val companyName = CompanySearchTerm.from(repository.loadCompanyProfile(symbol)?.name.orEmpty(), symbol)
        val trades = analytics.loadBrokerTrades(symbol, companyName).map { it.convertTo(targetCurrency) }
        return ChartData(bars, companyName, trades)
    }

    private fun BrokerTrade.convertTo(target: DisplayCurrency): BrokerTrade = copy(
        entryPrice = exchangeRates.convertCurrency(entryPrice, currency, target),
        exitPrice = exitPrice?.let { exchangeRates.convertCurrency(it, currency, target) },
        profitAmount = profitAmount?.let { exchangeRates.convertCurrency(it, currency, target) },
        currency = target.name
    )
}

internal data class ChartData(
    val bars: List<MinuteBar>,
    val companyName: String,
    val trades: List<BrokerTrade>
)
