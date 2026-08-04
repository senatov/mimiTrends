package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class TradegateInstrument(val isin: String, val name: String)

data class TradegateQuote(
    val isin: String,
    val last: Double,
    val bid: Double?,
    val ask: Double?,
    val bidSize: Double?,
    val askSize: Double?,
    val high: Double?,
    val low: Double?,
    val sessionVolume: Double?,
    val sessionTurnover: Double?,
    val averagePrice: Double?,
    val executions: Long?,
    val previousClose: Double?,
    val observedAtMillis: Long
)

class TradegateMarketDataClient(
    private val mapper: ObjectMapper = ObjectMapper(),
    cookieManager: CookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL),
    private val client: HttpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resolveInstrument(companyName: String): TradegateInstrument? {
        val query = URLEncoder.encode(companyName.trim(), StandardCharsets.UTF_8)
        val response = send(
            URI.create("$BASE_URL/kurssuche.php?suche=$query&lang=en"),
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "Tradegate instrument search")
        }
        val isin = ISIN_REGEX.find(response.body())?.groupValues?.get(1) ?: return null
        val name = NAME_REGEX.find(response.body())?.groupValues?.get(1)?.decodeHtml()?.trim().orEmpty()
        if (!isin.matches(VALID_ISIN)) return null
        log.debug(LogTag.API, "Tradegate instrument resolved query={} isin={} name={}", companyName, isin, name)
        return TradegateInstrument(isin, name.ifBlank { companyName })
    }

    fun loadQuote(isin: String): TradegateQuote {
        require(isin.matches(VALID_ISIN)) { "Invalid Tradegate ISIN" }
        val response = send(
            URI.create("$BASE_URL/refresh.php?isin=$isin"),
            "application/json, text/javascript, */*; q=0.01",
            "$BASE_URL/orderbuch_umsaetze.php?isin=$isin&lang=en"
        )
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "Tradegate quote for $isin")
        }
        return parseQuote(isin, response.body(), response.headers().firstValue("Date").orElse(null))
    }

    internal fun parseQuote(isin: String, body: String, dateHeader: String?): TradegateQuote {
        val json = mapper.readTree(body)
        val last = json.decimal("last") ?: error("Tradegate returned no last price for $isin")
        require(last.isFinite() && last > 0.0) { "Tradegate returned invalid last price for $isin" }
        return TradegateQuote(
            isin = isin,
            last = last,
            bid = json.decimal("bid"),
            ask = json.decimal("ask"),
            bidSize = json.decimal("bidsize"),
            askSize = json.decimal("asksize"),
            high = json.decimal("high"),
            low = json.decimal("low"),
            sessionVolume = json.decimal("stueck"),
            sessionTurnover = json.decimal("umsatz"),
            averagePrice = json.decimal("avg"),
            executions = json.path("executions").takeIf(JsonNode::isNumber)?.asLong(),
            previousClose = json.decimal("close"),
            observedAtMillis = parseDate(dateHeader) ?: System.currentTimeMillis()
        )
    }

    private fun send(uri: URI, accept: String, referer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", WINDOWS_USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
        referer?.let {
            builder.header("Referer", it).header("X-Requested-With", "XMLHttpRequest")
        }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun JsonNode.decimal(field: String): Double? {
        val node = path(field)
        val value = when {
            node.isNumber -> node.asDouble()
            node.isTextual -> node.asText().replace(" ", "").replace(',', '.').toDoubleOrNull()
            else -> null
        }
        return value?.takeIf(Double::isFinite)
    }

    private fun parseDate(value: String?): Long? = value?.let {
        runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun String.decodeHtml(): String = replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&nbsp;", " ")

    private companion object {
        const val BASE_URL = "https://www.tradegatebsx.com"
        const val WINDOWS_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val VALID_ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
        val ISIN_REGEX = Regex("var\\s+isin\\s*=\\s*\"([A-Z]{2}[A-Z0-9]{9}[0-9])\"")
        val NAME_REGEX = Regex("var\\s+securityName\\s*=\\s*\"([^\"]+)\"")
    }
}
