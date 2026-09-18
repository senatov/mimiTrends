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
