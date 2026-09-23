package org.senatov.mimitrends.providers

import javafx.application.Platform
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.marketdata.WallstreetOnlineMarketDataClient
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture

internal class StockPageOpener(
    private val repository: MarketRepository,
    private val client: WallstreetOnlineMarketDataClient,
    private val searchUrl: () -> String,
    private val openExternal: (String) -> Unit,
    private val setStatus: (String, Boolean, String?) -> Unit,
    private val formatError: (String, Throwable) -> String,
    private val log: Logger
) {
    fun open(symbol: String) {
        log.info(LogTag.UI, "open stock requested symbol={}", symbol)
        val isin = repository.loadInstrumentIsin(symbol)
        if (isin.isNullOrBlank()) {
            log.warn(LogTag.DB, "cannot open stock page because ISIN is unavailable symbol={}", symbol)
            setStatus("Cannot open stock page: no ISIN for $symbol", true, null)
            return
        }
        setStatus("Finding stock page: $symbol", false, null)
        CompletableFuture.supplyAsync { client.resolveStockUrl(searchUrl(), isin) }.whenComplete { url, error ->
            Platform.runLater {
                if (error == null && url != null) launch(symbol, url) else reportFailure(symbol, error)
            }
        }
    }

    private fun launch(symbol: String, url: String) {
        runCatching {
            log.info(LogTag.UI, "stock page resolved symbol={} url={}", symbol, url)
            openExternal(url)
        }.onSuccess {
            setStatus("Opened stock page: $symbol", false, null)
        }.onFailure { error ->
            reportFailure(symbol, error)
        }
    }

    private fun reportFailure(symbol: String, error: Throwable?) {
        log.warn(LogTag.API, "stock page lookup failed symbol={}", symbol, error)
        setStatus("Stock page not found: $symbol", true, error?.let { formatError(symbol, it) })
    }
}
