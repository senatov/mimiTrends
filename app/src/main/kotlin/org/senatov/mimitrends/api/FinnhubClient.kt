package org.senatov.mimitrends.api

import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.model.Candle
import org.senatov.mimitrends.model.InstrumentMatch
import org.senatov.mimitrends.model.MarketSnapshot
import org.senatov.mimitrends.model.Quote
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class FinnhubClient(
    private val apiKey: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val mapper: ObjectMapper = ObjectMapper()
) {
    private val log = LoggerFactory.getLogger(FinnhubClient::class.java)

    fun loadSnapshot(symbol: String, days: Long): CompletableFuture<MarketSnapshot> {
        log.debug(LogTag.API, "loadSnapshot(symbol={}, days={})", symbol, days)
        val normalizedSymbol = symbol.trim().uppercase()
        if (!isDirectSymbol(normalizedSymbol)) {
            return CompletableFuture.failedFuture(FinnhubException("Invalid market symbol: $symbol"))
        }
        val to = Instant.now().epochSecond
        val from = Instant.now().minus(days, ChronoUnit.DAYS).epochSecond

        val quoteFuture = get("/quote?symbol=${encode(normalizedSymbol)}").thenApply(::parseQuote)
        val candlesFuture = get(
            "/stock/candle?symbol=${encode(normalizedSymbol)}&resolution=D&from=$from&to=$to"
        ).handle { body, error ->
            if (error == null) parseCandles(body) else {
                log.warn(LogTag.API, "candle history unavailable symbol={}: {}", normalizedSymbol, error.message)
                emptyList()
            }
        }
        return quoteFuture.thenCombine(candlesFuture) { quote, candles ->
            log.info(LogTag.API, "snapshot loaded symbol={} candles={}", normalizedSymbol, candles.size)
            MarketSnapshot(normalizedSymbol, quote = quote, candles = candles)
        }.orTimeout(REQUEST_CHAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun resolveAndLoadSnapshot(query: String, days: Long): CompletableFuture<MarketSnapshot> {
        log.debug(LogTag.API, "resolveAndLoadSnapshot(query={}, days={})", query, days)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return CompletableFuture.failedFuture(FinnhubException("Enter a ticker, company name, ISIN or WKN"))
        }
        if (isLikelyTicker(normalizedQuery)) return loadSnapshot(normalizedQuery, days)
        return search(normalizedQuery).thenCompose { matches ->
            val match = matches.firstOrNull()
                ?: return@thenCompose CompletableFuture.failedFuture(
                    FinnhubException("No instrument found for '$normalizedQuery'")
                )
            loadSnapshot(match.symbol, days).thenApply { snapshot ->
                snapshot.copy(description = match.description)
            }
        }.orTimeout(REQUEST_CHAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    fun search(query: String): CompletableFuture<List<InstrumentMatch>> {
        log.debug(LogTag.API, "search(query={})", query)
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        return get("/search?q=${encode(normalizedQuery)}").thenApply { json ->
            parseSearchResults(json, normalizedQuery)
        }
    }

    internal fun parseSearchResults(json: String, query: String): List<InstrumentMatch> {
        log.debug(LogTag.API, "parseSearchResults(query={}, chars={})", query, json.length)
        val root = mapper.readTree(json)
        if (root.has("error")) throw FinnhubException(root.path("error").asText())
        return root.path("result").mapNotNull { item ->
            val symbol = item.path("symbol").asText().trim()
            if (symbol.isEmpty()) return@mapNotNull null
            InstrumentMatch(
                symbol = symbol,
                displaySymbol = item.path("displaySymbol").asText(symbol),
                description = item.path("description").asText(),
                type = item.path("type").asText()
            )
        }.sortedWith(
            compareByDescending<InstrumentMatch>(::isGermanInstrument)
                .thenByDescending { it.symbol.equals(query, ignoreCase = true) }
                .thenBy { it.description }
        )
    }

    internal fun parseQuote(json: String): Quote {
        log.debug(LogTag.API, "parseQuote(chars={})", json.length)
        val node = mapper.readTree(json)
        if (node.has("error")) throw FinnhubException(node.path("error").asText())
        val current = node.path("c").asDouble(Double.NaN)
        if (!current.isFinite() || current <= 0.0) {
            throw FinnhubException("Finnhub returned no quote for this symbol")
        }
        return Quote(
            current = current,
            change = node.path("d").asDouble(),
            percentChange = node.path("dp").asDouble(),
            high = node.path("h").asDouble(),
            low = node.path("l").asDouble(),
            open = node.path("o").asDouble(),
            previousClose = node.path("pc").asDouble()
        )
    }

    internal fun parseCandles(json: String): List<Candle> {
        log.debug(LogTag.API, "parseCandles(chars={})", json.length)
        val root = mapper.readTree(json)
        if (root.path("s").asText() != "ok") return emptyList()
        val timestamps = root.path("t")
        val closes = root.path("c")
        val size = minOf(timestamps.size(), closes.size())
        return (0 until size).mapNotNull { index ->
            val timestamp = timestamps[index].asLong()
            val close = closes[index].asDouble(Double.NaN)
            if (timestamp > 0 && close.isFinite()) Candle(timestamp, close) else null
        }
    }

    private fun get(path: String, attempt: Int = 0): CompletableFuture<String> {
        log.debug(LogTag.API, "get(path={}, attempt={})", path, attempt + 1)
        val separator = if ('?' in path) '&' else '?'
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$BASE_URL$path${separator}token=${encode(apiKey)}"))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET()
            .build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).handle { response, error ->
            when {
                error != null && attempt < RETRY_DELAYS_MS.size -> retry(path, attempt, error.message ?: "network error")
                error != null -> CompletableFuture.failedFuture(error)
                response.statusCode() in RETRYABLE_STATUS && attempt < RETRY_DELAYS_MS.size ->
                    retry(path, attempt, "HTTP ${response.statusCode()}")
                response.statusCode() !in 200..299 -> CompletableFuture.failedFuture(
                    FinnhubException("Finnhub request failed (HTTP ${response.statusCode()})")
                )
                else -> {
                    log.debug(LogTag.API, "response path={} status={} chars={}", path, response.statusCode(), response.body().length)
                    CompletableFuture.completedFuture(response.body())
                }
            }
        }.thenCompose { it }
    }

    private fun retry(path: String, attempt: Int, reason: String): CompletableFuture<String> {
        val delay = RETRY_DELAYS_MS[attempt]
        log.warn(LogTag.API, "temporary failure path={} reason={} retry={} delayMs={}", path, reason, attempt + 2, delay)
        return CompletableFuture.runAsync(
            {}, CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS)
        ).thenCompose { get(path, attempt + 1) }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).also {
            log.debug(LogTag.API, "encode(chars={})", value.length)
        }

    private fun isDirectSymbol(value: String): Boolean {
        log.debug(LogTag.API, "isDirectSymbol(value={})", value)
        return value.matches(Regex("[A-Z0-9.:-]{1,40}"))
    }

    private fun isLikelyTicker(value: String): Boolean {
        log.debug(LogTag.API, "isLikelyTicker(value={})", value)
        return value == value.uppercase() &&
            !value.equals("DAX", ignoreCase = true) &&
            value.matches(Regex("[A-Z]{1,6}([.:-][A-Z0-9]{1,8})?"))
    }

    private fun isGermanInstrument(match: InstrumentMatch): Boolean {
        log.debug(LogTag.API, "isGermanInstrument(symbol={})", match.symbol)
        return match.symbol.endsWith(".DE", ignoreCase = true) ||
            match.displaySymbol.endsWith(".DE", ignoreCase = true) ||
            match.description.contains("DAX", ignoreCase = true)
    }

    companion object {
        private const val BASE_URL = "https://finnhub.io/api/v1"
        private const val REQUEST_CHAIN_TIMEOUT_SECONDS = 15L
        private val RETRY_DELAYS_MS = longArrayOf(500, 1_500, 3_000)
        private val RETRYABLE_STATUS = setOf(502, 503, 504)
    }
}

class FinnhubException(message: String) : RuntimeException(message) {
    private companion object {
        private const val serialVersionUID = 1L
    }
}
