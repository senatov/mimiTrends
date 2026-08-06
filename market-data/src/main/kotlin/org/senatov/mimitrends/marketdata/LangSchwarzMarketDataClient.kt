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

data class LangSchwarzListing(
    val itemId: String,
    val wkn: String,
    val name: String,
    val path: String,
    val bid: Double,
    val ask: Double,
    val observedAtMillis: Long
) {
    val midpoint: Double get() = (bid + ask) / 2.0
}

class LangSchwarzMarketDataClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun loadEuropeanListings(): List<LangSchwarzListing> = URLS.flatMap { url ->
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "Lang & Schwarz listings")
        }
        parseListings(response.body())
    }.distinctBy(LangSchwarzListing::itemId)

    internal fun parseListings(
        html: String,
        date: LocalDate = LocalDate.now(QUOTE_ZONE),
        nowMillis: Long = System.currentTimeMillis()
    ): List<LangSchwarzListing> = ROW.findAll(html).mapNotNull { row ->
        val body = row.groupValues[1]
        val identity = IDENTITY.find(body) ?: return@mapNotNull null
        val itemId = ITEM_ID.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        val bid = quoteField(body, "bidWithCurrencySymbol") ?: return@mapNotNull null
        val ask = quoteField(body, "askWithCurrencySymbol") ?: return@mapNotNull null
        val time = field(body, "midTime")?.let { value -> runCatching { LocalTime.parse(value) }.getOrNull() }
            ?: return@mapNotNull null
        var observedAt = LocalDateTime.of(date, time).atZone(QUOTE_ZONE).toInstant().toEpochMilli()
        if (observedAt > nowMillis + FUTURE_TOLERANCE_MILLIS) observedAt -= Duration.ofDays(1).toMillis()
        LangSchwarzListing(
            itemId = itemId,
            wkn = identity.groupValues[1].trim().uppercase(),
            path = identity.groupValues[2],
            name = decode(identity.groupValues[3]).trim(),
            bid = bid,
            ask = ask,
            observedAtMillis = observedAt
        )
    }.filter { it.bid > 0.0 && it.ask >= it.bid }.toList()

    private fun quoteField(row: String, field: String): Double? = field(row, field)?.let(::germanNumber)

    private fun field(row: String, field: String): String? =
        Regex("field=[\"']$field[\"'][^>]*>(?:<[^>]+>)*([^<]+)", RegexOption.IGNORE_CASE)
            .find(row)?.groupValues?.get(1)?.let(::decode)?.trim()

    private fun germanNumber(value: String): Double? = value
        .replace(".", "")
        .replace(",", ".")
        .replace(Regex("[^0-9.+-]"), "")
        .toDoubleOrNull()

    private fun decode(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")

    private companion object {
        val URLS = listOf(
            "https://www.ls-tc.de/de/aktien/europa/stoxx50",
            "https://www.ls-tc.de/de/aktien/europa"
        )
        const val USER_AGENT = "MiMiTrends/1.0 (personal desktop market viewer)"
        val QUOTE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        const val FUTURE_TOLERANCE_MILLIS = 5 * 60_000L
        val ROW = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val IDENTITY = Regex(
            "<td[^>]*>\\s*<div[^>]*>\\s*([^<]+).*?<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([^<]+)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val ITEM_ID = Regex("item=[\"'](\\d+)@1[\"']", RegexOption.IGNORE_CASE)
    }
}
