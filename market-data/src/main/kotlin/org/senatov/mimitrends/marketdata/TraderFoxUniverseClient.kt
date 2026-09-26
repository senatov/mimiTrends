package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class TraderFoxList(val path: String) {
    DAX("dax-bestandteile"),
    NYSE("alle-nyse-aktien-bestandteile"),
    SP_500("sp-500-bestandteile"),
    NASDAQ_100("nasdaq-100-bestandteile")
}

data class TraderFoxEquity(
    val symbol: String,
    val currency: String,
    val price: Double,
    val changePercent: Double,
    val quoteEpochSeconds: Long
)

class TraderFoxUniverseClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val mapper: ObjectMapper = ObjectMapper()
) {
    fun load(list: TraderFoxList): List<TraderFoxEquity> {
        val request = HttpRequest.newBuilder(URI.create("$BASE_URL${list.path}"))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 MiMiTrends")
            .header("Accept", "text/html")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "TraderFox ${list.name} HTTP ${response.statusCode()}" }
        return parse(response.body())
    }

    internal fun parse(html: String): List<TraderFoxEquity> {
        val match = EQUITIES.find(html) ?: error("TraderFox equity data missing")
        val root = mapper.readTree(match.groupValues[1])
        require(root.isObject) { "TraderFox equity data is not an object" }
        return root.elements().asSequence().mapNotNull { equity ->
            val rawSymbol = equity.path("symbol").asText().trim().uppercase()
            val symbol = rawSymbol.replace('.', '-')
            val price = equity.path("v").asDouble(Double.NaN)
            val change = equity.path("p").asDouble(Double.NaN)
            val quoted = equity.path("t").asLong(0)
            if (equity.path("active").asText() != "Y" || !SYMBOL.matches(symbol) ||
                !price.isFinite() || price <= 0.0 || !change.isFinite() || quoted <= 0
            ) null else TraderFoxEquity(symbol, equity.path("currency").asText(), price, change, quoted)
        }.distinctBy(TraderFoxEquity::symbol).toList()
    }

    private companion object {
        const val BASE_URL = "https://markets.traderfox.com/aktien/"
        val EQUITIES = Regex("var\\s+equities\\s*=\\s*(\\{.*?});", RegexOption.DOT_MATCHES_ALL)
        val SYMBOL = Regex("[A-Z0-9][A-Z0-9-]{0,9}")
    }
}
