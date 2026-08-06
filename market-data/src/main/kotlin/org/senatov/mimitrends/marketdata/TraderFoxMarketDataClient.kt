package org.senatov.mimitrends.marketdata

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class TraderFoxQuote(
    val isin: String,
    val name: String,
    val last: Double,
    val currency: String,
    val observedAtMillis: Long
)

class TraderFoxMarketDataClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun loadQuote(isin: String): TraderFoxQuote {
        require(ISIN.matches(isin)) { "Invalid ISIN" }
        val request = HttpRequest.newBuilder(URI.create("$BASE_URL/${isin.uppercase()}/"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "TraderFox quote")
        }
        return parseQuote(response.body(), isin)
            ?: throw IllegalStateException("TraderFox quote payload is incomplete for $isin")
    }

    internal fun parseQuote(html: String, requestedIsin: String): TraderFoxQuote? {
        val payload = STOCK.findAll(html).map { it.groupValues[1] }
            .lastOrNull { JSON_ISIN.find(it)?.groupValues?.get(1)?.equals(requestedIsin, true) == true }
            ?: return null
        val isin = JSON_ISIN.find(payload)?.groupValues?.get(1) ?: return null
        val name = JSON_NAME.find(payload)?.groupValues?.get(1)?.let(::unescape) ?: return null
        val last = JSON_LAST.find(payload)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val timestamp = JSON_TIMESTAMP.find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val currency = JSON_CURRENCY.find(payload)?.groupValues?.get(1) ?: return null
        if (last <= 0.0 || timestamp <= 0L) return null
        return TraderFoxQuote(isin, name, last, currency, timestamp * 1_000L)
    }

    private fun unescape(value: String): String = value
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("\\\"", "\"")

    private companion object {
        const val BASE_URL = "https://aktie.traderfox.com/visualizations"
        const val USER_AGENT = "Mozilla/5.0 (compatible; MiMiTrends/1.0; personal desktop market viewer)"
        val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
        val STOCK = Regex("var\\s+stock\\s*=\\s*(\\{.*?});", RegexOption.DOT_MATCHES_ALL)
        val JSON_ISIN = Regex("\"isin\"\\s*:\\s*\"([^\"]+)\"")
        val JSON_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
        val JSON_LAST = Regex("\"v\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
        val JSON_TIMESTAMP = Regex("\"t\"\\s*:\\s*([0-9]+)")
        val JSON_CURRENCY = Regex("\"currency\"\\s*:\\s*\"([^\"]+)\"")
    }
}
