package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MarketSeries
import org.senatov.mimitrends.model.MinuteBar
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

class YahooFinanceClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val mapper: ObjectMapper = ObjectMapper()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun loadIntraday(symbol: String, afterEpochSeconds: Long? = null): MarketSeries {
        log.debug(LogTag.API, "loadIntraday(symbol={}, after={})", symbol, afterEpochSeconds)
        val normalized = symbol.trim().uppercase()
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8)
        val period = if (afterEpochSeconds == null) {
            "range=5d"
        } else {
            // Include two preceding minutes so an incomplete cached bar is safely replaced by SQLite UPSERT.
            "period1=${(afterEpochSeconds - 120).coerceAtLeast(0)}&period2=${Instant.now().epochSecond + 60}"
        }
        val uri = URI.create(
            "https://query1.finance.yahoo.com/v8/finance/chart/$encoded" +
                "?$period&interval=1m&includePrePost=false&events=div%2Csplits"
        )
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Yahoo Finance HTTP ${response.statusCode()} for $normalized" }
        return parse(normalized, response.body())
    }

    internal fun parse(symbol: String, body: String): MarketSeries {
        log.debug(LogTag.API, "parse(symbol={}, chars={})", symbol, body.length)
        val result = mapper.readTree(body).path("chart").path("result").firstOrNull()
            ?: error("Yahoo Finance returned no chart for $symbol")
        val meta = result.path("meta")
        val timestamps = result.path("timestamp")
        val quote = result.path("indicators").path("quote").firstOrNull()
            ?: error("Yahoo Finance returned no OHLCV data for $symbol")
        val bars = buildList {
            for (index in 0 until timestamps.size()) {
                val open = quote.numberAt("open", index) ?: continue
                val high = quote.numberAt("high", index) ?: continue
                val low = quote.numberAt("low", index) ?: continue
                val close = quote.numberAt("close", index) ?: continue
                val volume = quote.numberAt("volume", index) ?: 0.0
                add(MinuteBar(symbol, timestamps[index].asLong(), open, high, low, close, volume))
            }
        }
        check(bars.isNotEmpty()) { "Yahoo Finance returned empty OHLCV data for $symbol" }
        return MarketSeries(
            symbol = symbol,
            bars = bars,
            companyName = meta.path("shortName").asText().ifBlank {
                meta.path("longName").asText().ifBlank { symbol }
            },
            exchange = meta.path("fullExchangeName").asText().ifBlank {
                meta.path("exchangeName").asText().ifBlank { "Yahoo Finance" }
            },
            currency = meta.path("currency").asText()
        )
    }

    private fun JsonNode.numberAt(field: String, index: Int): Double? {
        val node = path(field).path(index)
        return if (node.isNumber) node.asDouble() else null
    }

    private companion object {
        const val USER_AGENT = "MiMiTrends/1.0 (personal desktop market viewer)"
    }
}
