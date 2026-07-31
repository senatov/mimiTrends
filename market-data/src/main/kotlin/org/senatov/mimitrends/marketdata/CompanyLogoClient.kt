package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
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
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val mapper: ObjectMapper = ObjectMapper()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun load(symbol: String, companyName: String): CompletableFuture<CompanyLogo?> {
        log.debug(LogTag.API, "loadLogo(symbol={}, company={})", symbol, companyName)
        KNOWN_DOMAINS[symbol.uppercase()]?.let { return loadDomain(it) }
        val searchName = companyName.substringBefore(' ').replace(Regex("[^A-Za-z0-9]"), "")
        val query = URLEncoder.encode(searchName, StandardCharsets.UTF_8)
        val search = request("https://api.loadlogo.com/search?q=$query")
        return httpClient.sendAsync(search, HttpResponse.BodyHandlers.ofString()).thenCompose { response ->
            if (response.statusCode() != 200) return@thenCompose CompletableFuture.completedFuture(null)
            val domain = mapper.readTree(response.body()).firstOrNull()?.path("domain")?.asText()?.takeIf(String::isNotBlank)
                ?: return@thenCompose CompletableFuture.completedFuture(null)
            loadDomain(domain)
        }.exceptionally { error ->
            log.warn(LogTag.API, "logo lookup failed company={}", companyName, error)
            null
        }
    }

    private fun loadDomain(domain: String): CompletableFuture<CompanyLogo?> {
        val imageUrl = "https://www.google.com/s2/favicons?domain=${URLEncoder.encode(domain, StandardCharsets.UTF_8)}&sz=64"
        return httpClient.sendAsync(request(imageUrl), HttpResponse.BodyHandlers.ofByteArray()).thenApply { image ->
            if (image.statusCode() != 200 || image.body().isEmpty()) null
            else CompanyLogo(imageUrl, image.body())
        }.exceptionally { error ->
            log.warn(LogTag.API, "logo download failed domain={}", domain, error)
            null
        }
    }

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("User-Agent", "MiMiTrends/1.0")
        .GET().build()

    private companion object {
        val KNOWN_DOMAINS = mapOf(
            "AAPL" to "apple.com", "MSFT" to "microsoft.com", "NVDA" to "nvidia.com",
            "AMZN" to "amazon.com", "META" to "meta.com", "GOOGL" to "google.com",
            "GOOG" to "google.com", "TSLA" to "tesla.com", "AVGO" to "broadcom.com",
            "JPM" to "jpmorganchase.com", "V" to "visa.com", "MA" to "mastercard.com",
            "LLY" to "lilly.com", "WMT" to "walmart.com", "ORCL" to "oracle.com",
            "NFLX" to "netflix.com", "AMD" to "amd.com", "COST" to "costco.com",
            "HD" to "homedepot.com", "BAC" to "bankofamerica.com", "XOM" to "exxonmobil.com",
            "CVX" to "chevron.com", "CRM" to "salesforce.com", "KO" to "coca-colacompany.com",
            "PEP" to "pepsico.com", "DIS" to "thewaltdisneycompany.com",
            "SAP.DE" to "sap.com", "SIE.DE" to "siemens.com", "ALV.DE" to "allianz.com",
            "DTE.DE" to "telekom.com", "BMW.DE" to "bmwgroup.com", "MBG.DE" to "group.mercedes-benz.com",
            "BAS.DE" to "basf.com", "RWE.DE" to "rwe.com", "DBK.DE" to "db.com", "DHL.DE" to "dhl.com",
            "ASML.AS" to "asml.com", "INGA.AS" to "ing.com", "AD.AS" to "aholddelhaize.com",
            "UNA.AS" to "unilever.com", "PHIA.AS" to "philips.com", "MC.PA" to "lvmh.com",
            "OR.PA" to "loreal.com", "TTE.PA" to "totalenergies.com", "AIR.PA" to "airbus.com",
            "BNP.PA" to "group.bnpparibas", "SAN.PA" to "sanofi.com", "SU.PA" to "se.com",
            "ENEL.MI" to "enel.com", "ISP.MI" to "group.intesasanpaolo.com", "STLAM.MI" to "stellantis.com"
        )
    }
}
