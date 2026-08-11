package org.senatov.mimitrends.marketdata

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class ArivaInstrumentReference(val isin: String, val wkn: String, val pageUrl: String)

class ArivaInstrumentClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun verify(isin: String): ArivaInstrumentReference {
        require(isin.matches(ISIN))
        val encoded = URLEncoder.encode(isin, StandardCharsets.UTF_8)
        val request = HttpRequest.newBuilder(URI.create("$SEARCH_URL?searchname=$encoded"))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "text/html")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "ARIVA instrument lookup")
        }
        return parseReference(response.body(), response.uri().toString())
            .takeIf { it.isin == isin }
            ?: throw ProviderDataUnavailableException("ARIVA returned a different instrument")
    }

    internal fun parseReference(html: String, responseUrl: String): ArivaInstrumentReference {
        val description = DESCRIPTION.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?: throw ProviderDataUnavailableException("ARIVA returned no instrument metadata")
        val identifiers = IDENTIFIERS.find(description)
            ?: throw ProviderDataUnavailableException("ARIVA returned incomplete instrument metadata")
        val canonical = CANONICAL.find(html)?.groupValues?.get(1) ?: responseUrl
        return ArivaInstrumentReference(
            isin = identifiers.groupValues[2].uppercase(),
            wkn = identifiers.groupValues[1].uppercase(),
            pageUrl = canonical
        )
    }

    private companion object {
        const val SEARCH_URL = "https://www.ariva.de/search/search.m"
        const val USER_AGENT = "MiMiTrends/1.0 private desktop market research"
        val ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
        val DESCRIPTION = Regex("""<meta\s+name=["']description["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val IDENTIFIERS = Regex("""WKN\s+([^ |]+)\s*\|\s*ISIN\s+([A-Z0-9]{12})""", RegexOption.IGNORE_CASE)
        val CANONICAL = Regex("""<link\s+rel=["']canonical["']\s+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    }
}
