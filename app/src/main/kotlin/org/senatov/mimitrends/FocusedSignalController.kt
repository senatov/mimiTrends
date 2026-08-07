package org.senatov.mimitrends

import javafx.application.Platform
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScanResult
import org.slf4j.Logger

internal class FocusedSignalController(
    evaluate: (String) -> ScanResult?,
    private val panel: ScannerPanel,
    private val isMarketOpen: (String) -> Boolean,
    private val onSelectedResult: (ScanResult) -> Unit,
    private val setStatus: (String, Boolean, String?) -> Unit,
    private val formatError: (String, Throwable) -> String,
    private val log: Logger
) : AutoCloseable {
    private val refresher = FocusedSignalRefresher(
        evaluate = evaluate,
        onLoading = { symbol, loading -> Platform.runLater { panel.setRefreshing(symbol, loading) } },
        onResult = { symbol, result -> Platform.runLater { applyResult(symbol, result) } },
        onError = { symbol, error -> Platform.runLater { showError(symbol, error) } }
    )

    fun request(result: ScanResult) {
        if (isMarketOpen(result.symbol)) refresher.request(result.symbol)
    }

    private fun applyResult(symbol: String, result: ScanResult?) {
        if (result == null) {
            panel.setRefreshing(symbol, false)
            setStatus("Focused refresh: $symbol has no qualifying current or long-term signal", false, null)
            return
        }
        panel.applyPriorityResult(symbol, result)
        onSelectedResult(result)
        setStatus("Focused signal refreshed: $symbol · ${result.dataStatus}", false, null)
    }

    private fun showError(symbol: String, error: Throwable) {
        log.warn(LogTag.API, "focused signal refresh failed symbol={}", symbol, error)
        setStatus("Focused refresh failed: $symbol · ${error.message ?: error.javaClass.simpleName}", true,
            formatError(symbol, error))
    }

    override fun close() = refresher.close()
}
