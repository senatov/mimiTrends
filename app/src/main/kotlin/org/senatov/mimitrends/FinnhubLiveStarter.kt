package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.ws.FinnhubMinuteAggregator
import org.senatov.mimitrends.ws.FinnhubWebSocketClient
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

internal object FinnhubLiveStarter {
    fun restart(
        key: String,
        previous: FinnhubWebSocketClient?,
        criteria: ScannerCriteria,
        liveTicks: ConcurrentHashMap<String, Long>,
        aggregator: FinnhubMinuteAggregator,
        log: Logger,
        setStatus: (String) -> Unit
    ): FinnhubWebSocketClient? {
        previous?.close()
        liveTicks.clear()
        if (key.isBlank()) return null
        val client = FinnhubWebSocketClient(
            apiKey = key,
            onTrade = java.util.function.Consumer { tick ->
                liveTicks[tick.symbol] = System.currentTimeMillis()
                aggregator.accept(tick)
            },
            onError = java.util.function.Consumer { error: Throwable ->
                log.warn(LogTag.API, "Finnhub live feed unavailable; Yahoo fallback remains active", error)
                javafx.application.Platform.runLater { setStatus("Finnhub unavailable · Yahoo/SQLite fallback active") }
            }
        )
        MarketUniverseSelector.select(criteria).filterNot { it.contains('.') }.forEach(client::subscribe)
        client.connect().whenComplete { _, error ->
            javafx.application.Platform.runLater {
                setStatus(if (error == null) "Finnhub live connected · Yahoo/SQLite history ready"
                    else "Finnhub connection failed · Yahoo/SQLite fallback active")
            }
        }
        return client
    }
}
