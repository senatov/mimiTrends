package org.senatov.mimitrends

import javafx.application.Platform
import org.senatov.mimitrends.charts.TrendChartView
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.ScanResult
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.function.BiConsumer

internal class ChartSelectionController(
    initialRange: String,
    private val chart: TrendChartView,
    private val loader: ChartDataLoader,
    private val displayCurrency: () -> DisplayCurrency,
    private val selectedSymbol: () -> String,
    private val selectedSignal: () -> ScanResult?,
    private val convertSignal: (ScanResult) -> ScanResult,
    private val isClosing: () -> Boolean,
    private val status: MainStatusController,
    private val formatError: (String, Throwable?) -> String,
    private val log: Logger
) {
    private val requests = LatestRequestGate<String>()
    var selectedRange: String = ChartRange.normalize(initialRange)
        private set

    fun selectRange(range: String) {
        if (range == selectedRange || range !in ChartRange.values) return
        selectedRange = range
        load(selectedSymbol())
    }

    fun load(symbol: String) {
        log.debug(LogTag.UI, "loadLocalChart(symbol={})", symbol)
        if (symbol.isBlank()) return
        chart.prepareForInstrument(symbol)
        val request = requests.begin(symbol)
        status.setLoading(true)
        val requestedRange = selectedRange
        val currency = displayCurrency()
        status.update("Requesting SQLite: $symbol · $requestedRange")
        CompletableFuture.supplyAsync {
            loader.load(symbol, ChartRange.days(requestedRange), currency)
        }.whenComplete(BiConsumer<ChartData?, Throwable?> { chartData, error ->
            Platform.runLater {
                if (isStale(request)) return@runLater
                status.setLoading(false)
                when {
                    error != null -> showError(symbol, error)
                    chartData != null && chartData.bars.isNotEmpty() -> showData(symbol, chartData, currency, requestedRange)
                    else -> showEmpty(symbol, requestedRange)
                }
            }
        })
    }

    fun invalidate() {
        requests.invalidate()
    }

    private fun isStale(request: LatestRequestGate.Request<String>): Boolean {
        val stale = !requests.accepts(request, selectedSymbol()) || isClosing()
        if (stale) log.debug(
            LogTag.UI, "discard stale chart load symbol={} generation={}", request.key, request.generation
        )
        return stale
    }

    private fun showData(symbol: String, data: ChartData, currency: DisplayCurrency, range: String) {
        val signal = selectedSignal()?.takeIf { it.symbol == symbol }?.let(convertSignal)
        chart.renderMinuteBars(symbol, data.bars, range, 1.0, currency.symbol, signal, data.companyName, data.trades)
        status.success("Read SQLite: $symbol · ${data.bars.size} minute bars · $range")
    }

    private fun showEmpty(symbol: String, range: String) {
        chart.showEmpty(symbol, range)
        status.warning("Read SQLite: no collected minute bars for $symbol · $range")
    }

    private fun showError(symbol: String, error: Throwable) {
        log.error(LogTag.DB, "local chart load failed symbol={}", symbol, error)
        chart.showError(symbol)
        status.update("SQLite read failed: ${error.message ?: "unknown error"}", true, formatError(symbol, error))
    }
}
