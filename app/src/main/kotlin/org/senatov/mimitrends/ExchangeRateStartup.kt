package org.senatov.mimitrends

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
