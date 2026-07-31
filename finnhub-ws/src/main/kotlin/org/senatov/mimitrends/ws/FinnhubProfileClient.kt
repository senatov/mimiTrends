package org.senatov.mimitrends.ws

import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.CompanyProfile
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer

class FinnhubProfileClient(
    private val apiKey: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build(),
    private val mapper: ObjectMapper = ObjectMapper()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CompletableFuture<CompanyProfile>>()

    fun load(symbol: String): CompletableFuture<CompanyProfile> {
        log.debug(LogTag.API, "load(symbol={})", symbol)
        val normalized = symbol.trim().uppercase()
        return cache.computeIfAbsent(normalized, ::request)
    }

    private fun request(symbol: String): CompletableFuture<CompanyProfile> {
        log.debug(LogTag.API, "request(symbol={})", symbol)
        val encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8)
        val encodedToken = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(
            URI.create("https://finnhub.io/api/v1/stock/profile2?symbol=$encodedSymbol&token=$encodedToken")
        ).timeout(Duration.ofSeconds(10)).GET().build()

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                check(response.statusCode() == 200) { "Finnhub profile HTTP ${response.statusCode()}" }
                val json = mapper.readTree(response.body())
                CompanyProfile(
                    symbol = symbol,
                    name = json.path("name").asText().ifBlank { symbol },
                    exchange = json.path("exchange").asText().ifBlank { "Trading venue unavailable" },
                    logoUrl = json.path("logo").asText().takeIf { it.startsWith("https://") }
                )
            }.thenCompose(::loadLogo).whenComplete(BiConsumer<CompanyProfile?, Throwable?> { _, error ->
                if (error != null) {
                    cache.remove(symbol)
                    log.warn(LogTag.API, "company profile load failed symbol={}", symbol, error)
                }
            })
    }

    private fun loadLogo(profile: CompanyProfile): CompletableFuture<CompanyProfile> {
        log.debug(LogTag.API, "loadLogo(symbol={}, hasUrl={})", profile.symbol, profile.logoUrl != null)
        val url = profile.logoUrl ?: return CompletableFuture.completedFuture(profile)
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build()
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply { response ->
            if (response.statusCode() == 200) profile.copy(logoBytes = response.body()) else profile
        }.exceptionally { error ->
            log.warn(LogTag.API, "company logo load failed symbol={}", profile.symbol, error)
            profile
        }
    }
}
