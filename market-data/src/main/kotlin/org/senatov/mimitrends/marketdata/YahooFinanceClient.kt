package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MarketSeries
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import org.senatov.mimitrends.model.MarketEvent
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
        val period = if (afterEpochSeconds == null) {
            "range=7d"
        } else {
            // Include two preceding minutes so an incomplete cached bar is safely replaced by SQLite UPSERT.
            "period1=${(afterEpochSeconds - 120).coerceAtLeast(0)}&period2=${Instant.now().epochSecond + 60}"
        }
        return try {
            requestSeries(normalized, period)
        } catch (error: YahooEmptyOhlcvException) {
            if (afterEpochSeconds == null) throw error
            log.debug(LogTag.API, "incremental Yahoo response empty; retrying full window symbol={}", normalized)
            requestSeries(normalized, "range=7d")
        }
    }

    fun loadDayGainers(limit: Int = 25): List<YahooMarketLeader> {
        val safeLimit = limit.coerceIn(1, MAX_DISCOVERY_RESULTS)
        val uri = URI.create(
            "https://$PRIMARY_HOST/v1/finance/screener/predefined/saved" +
                "?scrIds=day_gainers&count=$safeLimit&start=0"
        )
        val response = sendWithRetry(request(uri))
        check(response.statusCode() == 200) { "Yahoo Finance screener HTTP ${response.statusCode()}" }
        return parseDayGainers(response.body(), safeLimit)
    }

    internal fun parseDayGainers(body: String, limit: Int = 25): List<YahooMarketLeader> {
        val quotes = mapper.readTree(body).path("finance").path("result").firstOrNull()?.path("quotes")
            ?: return emptyList()
        return quotes.mapNotNull { quote ->
            val symbol = quote.path("symbol").asText().trim().uppercase()
            val price = quote.path("regularMarketPrice").asDouble(Double.NaN)
            val change = quote.path("regularMarketChangePercent").asDouble(Double.NaN)
            val type = quote.path("quoteType").asText()
            val exchange = quote.path("exchange").asText()
            if (symbol.isBlank() || !price.isFinite() || price <= 0.0 || !change.isFinite() ||
                type != "EQUITY" || exchange !in US_EXCHANGES) null
            else YahooMarketLeader(symbol, price, change)
        }.distinctBy(YahooMarketLeader::symbol)
            .sortedByDescending(YahooMarketLeader::changePercent)
            .take(limit.coerceIn(1, MAX_DISCOVERY_RESULTS))
    }

    private fun requestSeries(normalized: String, period: String): MarketSeries {
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8)
        val pathAndQuery = "/v8/finance/chart/$encoded" +
                "?$period&interval=1m&includePrePost=false&events=div%2Csplits"
        var response = sendWithRetry(request(URI.create("https://$PRIMARY_HOST$pathAndQuery")))
        if (response.statusCode() == 404) {
            log.debug(LogTag.API, "Yahoo primary host returned 404; retrying alternate host symbol={}", normalized)
            response = sendWithRetry(request(URI.create("https://$ALTERNATE_HOST$pathAndQuery")))
        }
        check(response.statusCode() == 200) { "Yahoo Finance HTTP ${response.statusCode()} for $normalized" }
        return parse(normalized, response.body())
    }

    private fun request(uri: URI): HttpRequest = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .GET()
            .build()

    private fun sendWithRetry(request: HttpRequest): HttpResponse<String> {
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        repeat(MAX_SERVER_RETRIES) { attempt ->
            if (response.statusCode() !in 500..599) return response
            Thread.sleep(RETRY_DELAY_MILLIS * (attempt + 1))
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        }
        return response
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
                val reportedVolume = quote.numberAt("volume", index)
                val volume = reportedVolume ?: 0.0
                val volumeStatus = when {
                    reportedVolume == null -> VolumeStatus.MISSING
                    reportedVolume > 0.0 -> VolumeStatus.REPORTED
                    else -> VolumeStatus.ZERO
                }
                val minuteEpoch = timestamps[index].asLong() / 60L * 60L
                add(MinuteBar(symbol, minuteEpoch, open, high, low, close, volume, volumeStatus))
            }
        }
        if (bars.isEmpty()) throw YahooEmptyOhlcvException(symbol)
        val events = buildList {
            result.path("events").path("splits").properties().forEach { (_, event) ->
                val numerator = event.path("numerator").asDouble(Double.NaN)
                val denominator = event.path("denominator").asDouble(Double.NaN)
                val ratio = if (numerator.isFinite() && denominator.isFinite() && denominator != 0.0) numerator / denominator else null
                add(MarketEvent("SPLIT", event.path("date").asLong(), ratio = ratio))
            }
            result.path("events").path("dividends").properties().forEach { (_, event) ->
                add(MarketEvent("DIVIDEND", event.path("date").asLong(), amount = event.path("amount").asDouble(),
                    currency = meta.path("currency").asText().ifBlank { null }))
            }
        }
        return MarketSeries(
            symbol = symbol,
            bars = bars,
            companyName = meta.path("shortName").asText().ifBlank {
                meta.path("longName").asText().ifBlank { symbol }
            },
            exchange = meta.path("fullExchangeName").asText().ifBlank {
                meta.path("exchangeName").asText().ifBlank { "Yahoo Finance" }
            },
            currency = meta.path("currency").asText(),
            events = events
        )
    }

    private fun JsonNode.numberAt(field: String, index: Int): Double? {
        val node = path(field).path(index)
        return if (node.isNumber) node.asDouble() else null
    }

    private companion object {
        const val USER_AGENT = "MiMiTrends/1.0 (personal desktop market viewer)"
        const val PRIMARY_HOST = "query1.finance.yahoo.com"
        const val ALTERNATE_HOST = "query2.finance.yahoo.com"
        const val MAX_SERVER_RETRIES = 2
        const val RETRY_DELAY_MILLIS = 250L
        const val MAX_DISCOVERY_RESULTS = 50
        val US_EXCHANGES = setOf("NMS", "NYQ", "NGM", "NCM", "ASE", "PCX", "BTS")
    }
}

data class YahooMarketLeader(val symbol: String, val price: Double, val changePercent: Double)

internal class YahooEmptyOhlcvException(symbol: String) :
    RuntimeException("Yahoo Finance returned empty OHLCV data for $symbol") {
    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
