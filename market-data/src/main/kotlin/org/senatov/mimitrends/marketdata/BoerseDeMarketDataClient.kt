package org.senatov.mimitrends.marketdata

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BoerseDeQuote(val last: Double, val currency: String, val observedAtMillis: Long)

class BoerseDeMarketDataClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun loadQuote(isin: String): BoerseDeQuote {
        require(isin.matches(VALID_ISIN))
        val request = HttpRequest.newBuilder(URI.create("$BASE_URL/realtime-kurse/x/$isin"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "boerse.de quote for $isin")
        }
        return parseQuote(response.body())
    }

    internal fun parseQuote(html: String): BoerseDeQuote {
        val price = PRICE.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: throw ProviderDataUnavailableException("boerse.de returned no current price")
        val time = field(html, "timestamp")
            ?: throw ProviderDataUnavailableException("boerse.de returned no quote time")
        val date = field(html, "date")
            ?: throw ProviderDataUnavailableException("boerse.de returned no quote date")
        val observedAt = LocalDateTime.parse("$date $time", QUOTE_TIME)
            .atZone(QUOTE_ZONE).toInstant().toEpochMilli()
        val currency = CURRENCY.find(html)?.groupValues?.get(1)?.trim()?.uppercase() ?: "EUR"
        return BoerseDeQuote(price, currency, observedAt)
    }

    private fun field(html: String, attribute: String): String? =
        Regex("data-push-attribute=[\"']$attribute[\"'][^>]*>\\s*([^<]+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()

    private companion object {
        const val BASE_URL = "https://www.boerse.de"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/537.36 Chrome/131 Safari/537.36"
        val VALID_ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
        val PRICE = Regex("itemprop=[\"']price[\"']\\s+content=[\"']([0-9.]+)[\"']", RegexOption.IGNORE_CASE)
        val CURRENCY = Regex("itemprop=[\"']priceCurrency[\"'][^>]*>\\s*([A-Z]{3})", RegexOption.IGNORE_CASE)
        val QUOTE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm:ss")
        val QUOTE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
