package org.senatov.mimitrends

import javafx.application.Platform
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScanResult
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

internal class FocusedSignalController(
    evaluate: (String) -> ScanResult?,
    refreshQuote: (ScanResult) -> ScanResult,
    private val panel: ScannerPanel,
    private val isMarketOpen: (String) -> Boolean,
    private val onSelectedResult: (ScanResult) -> Unit,
    private val setStatus: (String, Boolean, String?) -> Unit,
    private val formatError: (String, Throwable) -> String,
    private val log: Logger
) : AutoCloseable {
    private val requestedResults = ConcurrentHashMap<String, ScanResult>()
    private val refresher = FocusedSignalRefresher(
        evaluate = { symbol ->
            val evaluated = evaluate(symbol)
            resolveFocusedSignalRefresh(evaluated, requestedResults[symbol], refreshQuote)
        },
        onLoading = { symbol, loading -> Platform.runLater { panel.setRefreshing(symbol, loading) } },
        onResult = { symbol, refresh -> Platform.runLater { applyResult(symbol, refresh) } },
        onError = { symbol, error -> Platform.runLater { showError(symbol, error) } }
    )

    fun request(result: ScanResult) {
        if (isMarketOpen(result.symbol)) {
            requestedResults[result.symbol.uppercase()] = result
            refresher.request(result.symbol)
        }
    }

    private fun applyResult(symbol: String, refresh: FocusedSignalRefresh) {
        val result = refresh.result
        if (result == null) {
            panel.setRefreshing(symbol, false)
            setStatus("Focused refresh: $symbol has no qualifying current or long-term signal", false, null)
            return
        }
        panel.applyPriorityResult(symbol, result)
        onSelectedResult(result)
        val message = if (refresh.qualifyingSignal) {
            "Focused signal refreshed: $symbol · ${result.dataStatus}"
        } else {
            "Focused quote refreshed: $symbol · no qualifying current or long-term signal"
        }
        setStatus(message, false, null)
    }

    private fun showError(symbol: String, error: Throwable) {
        log.warn(LogTag.API, "focused signal refresh failed symbol={}", symbol, error)
        setStatus("Focused refresh failed: $symbol · ${error.message ?: error.javaClass.simpleName}", true,
            formatError(symbol, error))
    }

    override fun close() {
        requestedResults.clear()
        refresher.close()
    }
}

internal data class FocusedSignalRefresh(val result: ScanResult?, val qualifyingSignal: Boolean)

internal fun resolveFocusedSignalRefresh(
    evaluated: ScanResult?,
    requested: ScanResult?,
    refreshQuote: (ScanResult) -> ScanResult
): FocusedSignalRefresh = if (evaluated != null) {
    FocusedSignalRefresh(evaluated, true)
} else {
    FocusedSignalRefresh(requested?.let(refreshQuote), false)
}
