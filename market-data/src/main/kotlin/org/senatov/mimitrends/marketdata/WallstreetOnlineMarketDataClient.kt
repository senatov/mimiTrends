package org.senatov.mimitrends.marketdata

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class WallstreetOnlineMover(
    val name: String,
    val path: String,
    val price: Double,
    val changePercent: Double
)

data class WallstreetOnlineQuote(
    val isin: String,
    val name: String,
    val last: Double,
    val bid: Double?,
    val ask: Double?,
    val currency: String,
    val venue: String,
    val observedAtMillis: Long
)

class WallstreetOnlineMarketDataClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun loadMovers(): List<WallstreetOnlineMover> = MOVER_PATHS.flatMap { path ->
        parseMovers(send("$BASE_URL$path"))
    }.distinctBy(WallstreetOnlineMover::path)

    fun loadQuote(path: String): WallstreetOnlineQuote {
        require(DETAIL_PATH.matches(path)) { "Invalid wallstreetONLINE instrument path" }
        return parseQuote(send("$BASE_URL$path"))
    }

    internal fun parseMovers(html: String): List<WallstreetOnlineMover> = ROW.findAll(html).mapNotNull { row ->
        val body = row.groupValues[1]
        val link = DETAIL_LINK.find(body) ?: return@mapNotNull null
        val price = PRICE.find(body)?.groupValues?.get(1)?.germanNumber() ?: return@mapNotNull null
        val change = CHANGE.find(body)?.groupValues?.get(1)?.germanNumber() ?: return@mapNotNull null
        WallstreetOnlineMover(decode(link.groupValues[2]).trim(), link.groupValues[1], price, change)
    }.take(MAX_MOVERS_PER_PAGE).toList()

    internal fun parseQuote(
        html: String,
        date: LocalDate = LocalDate.now(QUOTE_ZONE),
        nowMillis: Long = System.currentTimeMillis()
    ): WallstreetOnlineQuote {
        val isin = ISIN.find(html)?.groupValues?.get(1)
            ?: throw ProviderDataUnavailableException("wallstreetONLINE returned no ISIN")
        val name = NAME.find(html)?.groupValues?.get(1)?.let(::decode)?.trim()
            ?: throw ProviderDataUnavailableException("wallstreetONLINE returned no instrument name")
        val last = LAST.find(html)?.groupValues?.get(1)?.germanNumber()
            ?: throw ProviderDataUnavailableException("wallstreetONLINE returned no current price")
        val quoteInfo = QUOTE_INFO.find(html)
            ?: throw ProviderDataUnavailableException("wallstreetONLINE returned no quote time")
        val time = runCatching { LocalTime.parse(quoteInfo.groupValues[1]) }.getOrElse {
            throw ProviderDataUnavailableException("wallstreetONLINE returned an invalid quote time")
        }
        var observedAt = LocalDateTime.of(date, time).atZone(QUOTE_ZONE).toInstant().toEpochMilli()
        if (observedAt > nowMillis + FUTURE_TOLERANCE_MILLIS) observedAt -= Duration.ofDays(1).toMillis()
        return WallstreetOnlineQuote(
            isin = isin,
            name = name.removeSuffix(" Aktie").trim(),
            last = last,
            bid = BID.find(html)?.groupValues?.get(1)?.germanNumber(),
            ask = ASK.find(html)?.groupValues?.get(1)?.germanNumber(),
            currency = CURRENCY.find(html)?.groupValues?.get(1)?.trim()?.uppercase() ?: "EUR",
            venue = quoteInfo.groupValues[2].trim(),
            observedAtMillis = observedAt
        )
    }

    private fun send(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT).header("Accept", "text/html,application/xhtml+xml").GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "wallstreetONLINE page")
        }
        return response.body()
    }

    private fun String.germanNumber(): Double? = replace(".", "").replace(",", ".")
        .replace(Regex("[^0-9.+-]"), "").toDoubleOrNull()

    private fun decode(value: String): String = value.replace("&amp;", "&").replace("&quot;", "\"")

    private companion object {
        const val BASE_URL = "https://www.wallstreet-online.de"
        const val USER_AGENT = "MiMiTrends/1.0 (personal desktop market viewer)"
        const val MAX_MOVERS_PER_PAGE = 50
        const val FUTURE_TOLERANCE_MILLIS = 5 * 60_000L
        val QUOTE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val MOVER_PATHS = listOf("/statistik/top-aktien-performance", "/statistik/flop-aktien-performance")
        val DETAIL_PATH = Regex("/aktien/[a-z0-9-]+-aktie")
        val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val DETAIL_LINK = Regex("<a[^>]+href=[\"'](/aktien/[a-z0-9-]+-aktie)[\"'][^>]*>([^<]+)</a>", RegexOption.IGNORE_CASE)
        val PRICE = Regex("data-push=[\"'][^\"']+;t[\"'][^>]*>\\s*([^<]+)", RegexOption.IGNORE_CASE)
        val CHANGE = Regex("class=[\"']drel right[\"'].*?class=[\"']font [^\"']+[\"']>\\s*([+-]?[0-9.,]+)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val ISIN = Regex("class=[\"']cpyt isin value[\"']>([A-Z]{2}[A-Z0-9]{9}[0-9])<", RegexOption.IGNORE_CASE)
        val NAME = Regex("<h1[^>]*class=[\"'][^\"']*product-heading-heading[^\"']*[\"'][^>]*>([^<]+)</h1>", RegexOption.IGNORE_CASE)
        val LAST = Regex("class=[\"']float-start quoteValue[\"']>\\s*<span[^>]+;t[\"'][^>]*>\\s*([^<]+)", RegexOption.IGNORE_CASE)
        val QUOTE_INFO = Regex("Letzter Kurs\\s*<span[^>]+;tt[\"'][^>]*>\\s*([0-9:]+)</span>\\s*([^<]+)", RegexOption.IGNORE_CASE)
        val BID = Regex("id=[\"']bid[\"'][^>]*>\\s*<span[^>]*>\\s*([^<]+)", RegexOption.IGNORE_CASE)
        val ASK = Regex("id=[\"']ask[\"'][^>]*>\\s*<span[^>]*>\\s*([^<]+)", RegexOption.IGNORE_CASE)
        val CURRENCY = Regex("<div table=[\"']quotes[\"'] class=[\"']quote_currency[\"']>([A-Z]{3})</div>", RegexOption.IGNORE_CASE)
    }
}
