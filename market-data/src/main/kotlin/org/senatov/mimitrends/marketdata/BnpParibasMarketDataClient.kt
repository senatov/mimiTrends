package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

data class BnpParibasQuote(val last: Double, val currency: String, val observedAtMillis: Long)

class BnpParibasMarketDataClient(
    private val mapper: ObjectMapper = ObjectMapper(),
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun loadQuote(isin: String): BnpParibasQuote {
        require(isin.matches(VALID_ISIN))
        val request = HttpRequest.newBuilder(URI.create("$BASE_URL/apiv2/api/v1/underlying/header/$isin"))
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .header("clientid", "0")
            .header("languageid", "de")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "BNP Paribas quote for $isin")
        }
        return parseQuote(response.body())
    }

    internal fun parseQuote(json: String): BnpParibasQuote {
        val result = mapper.readTree(json).path("result")
        val price = result.path("price").asDouble(Double.NaN)
        if (!price.isFinite() || price <= 0.0 || !result.path("isPriceToday").asBoolean(false)) {
            throw ProviderDataUnavailableException("BNP Paribas returned no current indication")
        }
        val priceDate = result.path("priceDate").asText()
        val observedAt = runCatching {
            LocalDateTime.parse(priceDate).atZone(QUOTE_ZONE).toInstant().toEpochMilli()
        }.getOrElse { throw ProviderDataUnavailableException("BNP Paribas returned an invalid quote time") }
        val currency = result.path("currency").path("isoCode").asText("EUR")
        return BnpParibasQuote(price, currency, observedAt)
    }

    private companion object {
        const val BASE_URL = "https://derivate.bnpparibas.com"
        val VALID_ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
        val QUOTE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
