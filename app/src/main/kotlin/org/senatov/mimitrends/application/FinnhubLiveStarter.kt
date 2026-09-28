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

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.ws.FinnhubMinuteAggregator
import org.senatov.mimitrends.ws.FinnhubWebSocketClient
import org.senatov.mimitrends.providers.SourceActivity
import org.slf4j.Logger
import java.util.concurrent.ConcurrentHashMap

internal object FinnhubLiveStarter {
    fun restart(
        key: String,
        previous: FinnhubWebSocketClient?,
        liveTicks: ConcurrentHashMap<String, Long>,
        aggregator: FinnhubMinuteAggregator,
        log: Logger,
        setStatus: (String) -> Unit,
        activity: SourceActivity? = null
    ): FinnhubWebSocketClient? {
        previous?.close()
        liveTicks.clear()
        if (key.isBlank()) {
            activity?.markStatus("Finnhub", "No API key")
            return null
        }
        activity?.markStatus("Finnhub", "Connecting")
        val client = FinnhubWebSocketClient(
            apiKey = key,
            onTrade = java.util.function.Consumer { tick ->
                activity?.record("Finnhub", 1, 1)
                liveTicks[tick.symbol] = System.currentTimeMillis()
                aggregator.accept(tick)
            },
            onError = java.util.function.Consumer { error: Throwable ->
                activity?.markStatus("Finnhub", "Disconnected", failed = true)
                log.warn(LogTag.API, "Finnhub live feed unavailable; Yahoo fallback remains active", error)
                javafx.application.Platform.runLater { setStatus("Finnhub unavailable · Yahoo/SQLite fallback active") }
            }
        )
        client.connect().whenComplete { _, error ->
            activity?.markStatus("Finnhub", if (error == null) "Connected" else "Disconnected", error != null)
            javafx.application.Platform.runLater {
                setStatus(
                    if (error == null) "Finnhub live connected · Yahoo/SQLite history ready"
                    else "Finnhub connection failed · Yahoo/SQLite fallback active"
                )
            }
        }
        return client
    }
}
