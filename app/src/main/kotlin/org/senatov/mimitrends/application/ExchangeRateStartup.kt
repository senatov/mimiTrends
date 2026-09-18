package org.senatov.mimitrends.application

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

import javafx.application.Platform
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.DisplayCurrency
import org.slf4j.Logger
import java.util.function.BiConsumer

internal class ExchangeRateStartup(
    private val exchangeRates: ExchangeRateService,
    private val analytics: AnalyticsRepository,
    private val scannerPanel: ScannerPanel,
    private val displayCurrency: () -> DisplayCurrency,
    private val convertPrice: (String, Double) -> Double,
    private val reloadChart: () -> Unit,
    private val setStatus: (String) -> Unit,
    private val log: Logger
) {
    fun start() {
        setStatus("Requesting ECB EUR/USD reference rate")
        exchangeRates.refresh().whenComplete(BiConsumer<Double?, Throwable?> { rate, error ->
            if (error != null) log.warn(LogTag.API, "ECB exchange-rate refresh failed; cached rate remains active", error)
            if (error == null && rate != null) analytics.recordFxRate("EUR", "USD", rate, "ECB")
            Platform.runLater {
                scannerPanel.setCurrency(displayCurrency(), convertPrice)
                reloadChart()
                if (error == null && rate != null) setStatus("Read ECB EUR/USD reference rate: $rate")
            }
        })
    }
}
