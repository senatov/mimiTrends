package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.CompanyDomain
import org.senatov.mimitrends.model.CompanyDomainSource
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

data class CompanyLogo(val sourceUrl: String, val pngBytes: ByteArray)

class CompanyLogoClient(
    private val domainLookup: (String) -> CompanyDomain? = { null },
    private val domainRecorder: (CompanyDomain) -> Unit = {},
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val mapper: ObjectMapper = ObjectMapper(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun load(symbol: String, companyName: String): CompletableFuture<CompanyLogo?> {
        val normalizedSymbol = symbol.trim().uppercase()
        log.debug(LogTag.API, "loadLogo(symbol={}, company={})", normalizedSymbol, companyName)
        return CompletableFuture.supplyAsync { domainLookup(normalizedSymbol) }.thenCompose { stored ->
            if (stored != null) loadStored(stored) else search(normalizedSymbol, companyName)
        }
    }

    private fun loadStored(stored: CompanyDomain): CompletableFuture<CompanyLogo?> {
        val now = nowMillis()
        if (stored.failureCount >= FAILURE_LIMIT && now - stored.updatedAtMillis < NEGATIVE_CACHE_MILLIS) {
            log.debug(LogTag.API, "logo domain retry suppressed symbol={} failures={}", stored.symbol, stored.failureCount)
            return CompletableFuture.completedFuture(null)
        }
        return loadDomain(stored.domain).thenApply { logo ->
            record(if (logo == null) stored.failed(now) else stored.succeeded(now))
            logo
        }
    }

    private fun search(symbol: String, companyName: String): CompletableFuture<CompanyLogo?> {
        val query = URLEncoder.encode(companyName.trim(), StandardCharsets.UTF_8)
        if (query.isBlank()) return CompletableFuture.completedFuture(null)
        return httpClient.sendAsync(request("https://api.loadlogo.com/search?q=$query"), HttpResponse.BodyHandlers.ofString())
            .thenCompose { response ->
                if (response.statusCode() != 200) return@thenCompose CompletableFuture.completedFuture(null)
                val match = CompanyDomainMatcher.select(mapper.readTree(response.body()), companyName)
                    ?: return@thenCompose CompletableFuture.completedFuture(null)
                val candidate = CompanyDomain(symbol, match.domain, CompanyDomainSource.SEARCH, match.confidence)
                loadDomain(candidate.domain).thenApply { logo ->
                    if (logo != null) record(candidate.succeeded(nowMillis()))
                    logo
                }
            }.exceptionally { error ->
                log.warn(LogTag.API, "logo lookup failed symbol={} company={}", symbol, companyName, error)
                null
            }
    }

    private fun loadDomain(domain: String): CompletableFuture<CompanyLogo?> {
        val imageUrl = "https://www.google.com/s2/favicons?domain=${encode(domain)}&sz=64"
        return httpClient.sendAsync(request(imageUrl), HttpResponse.BodyHandlers.ofByteArray()).thenApply { image ->
            if (image.statusCode() != 200 || image.body().isEmpty()) null else CompanyLogo(imageUrl, image.body())
        }.exceptionally { error ->
            log.warn(LogTag.API, "logo download failed domain={}", domain, error)
            null
        }
    }

    private fun record(domain: CompanyDomain) = runCatching { domainRecorder(domain) }
        .onFailure { error -> log.warn(LogTag.DB, "company domain update failed symbol={}", domain.symbol, error) }

    private fun CompanyDomain.succeeded(now: Long) = copy(
        confidence = (confidence + SUCCESS_CONFIDENCE_INCREMENT).coerceAtMost(1.0),
        verifiedAtMillis = now, lastSuccessAtMillis = now, failureCount = 0, updatedAtMillis = now
    )

    private fun CompanyDomain.failed(now: Long) = copy(
        confidence = (confidence - FAILURE_CONFIDENCE_DECREMENT).coerceAtLeast(0.0),
        failureCount = failureCount + 1, updatedAtMillis = now
    )

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10)).header("User-Agent", "MiMiTrends/1.0").GET().build()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val FAILURE_LIMIT = 3
        const val NEGATIVE_CACHE_MILLIS = 24 * 60 * 60 * 1_000L
        const val SUCCESS_CONFIDENCE_INCREMENT = 0.02
        const val FAILURE_CONFIDENCE_DECREMENT = 0.15
    }
}

internal object CompanyDomainMatcher {
    data class Match(val domain: String, val confidence: Double)

    fun select(results: JsonNode, companyName: String): Match? = results.mapNotNull { candidate ->
        val domain = candidate.path("domain").asText().normalizeDomain() ?: return@mapNotNull null
        Match(domain, score(companyName, candidate.path("name").asText(), domain))
    }.maxByOrNull(Match::confidence)?.takeIf { it.confidence >= MIN_CONFIDENCE }

    private fun score(companyName: String, candidateName: String, domain: String): Double {
        val expected = tokens(companyName)
        if (expected.isEmpty()) return 0.0
        val described = tokens(candidateName)
        val nameScore = if (described.isEmpty()) 0.0 else expected.intersect(described).size.toDouble() /
                expected.union(described).size
        val domainText = domain.substringBeforeLast('.').replace(Regex("[^a-z0-9]"), "")
        val domainScore = when {
            expected.size == 1 && domainText == expected.single() -> 1.0
            expected.size == 1 && expected.single().length >= 4 && domainText.contains(expected.single()) -> 0.75
            expected.size > 1 && domainText == expected.joinToString("") -> 0.9
            else -> 0.0
        }
        return maxOf(nameScore, domainScore).coerceIn(0.0, 1.0)
    }

    private fun tokens(value: String): Set<String> = value.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in LEGAL_SUFFIXES }.toSet()

    private fun String.normalizeDomain(): String? {
        val normalized = trim().lowercase().removePrefix("https://").removePrefix("http://")
            .substringBefore('/').removePrefix("www.")
        return normalized.takeIf(DOMAIN::matches)
    }

    private val DOMAIN = Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")
    private val LEGAL_SUFFIXES = setOf(
        "inc", "incorporated", "corp", "corporation", "company", "co", "ltd", "plc", "ag", "se", "sa",
        "spa", "gmbh", "group"
    )
    private const val MIN_CONFIDENCE = 0.5
}