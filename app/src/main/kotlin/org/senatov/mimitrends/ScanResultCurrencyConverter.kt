package org.senatov.mimitrends

import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria

internal class ScanResultCurrencyConverter(
    private val exchangeRates: ExchangeRateService,
    private val criteria: () -> ScannerCriteria
) {
    fun price(symbol: String, value: Double): Double =
        exchangeRates.convert(symbol, value, criteria().displayCurrency)

    fun result(value: ScanResult): ScanResult = value.copy(
        price = price(value.symbol, value.price),
        signalPrice = price(value.symbol, value.signalPrice)
    )
}
