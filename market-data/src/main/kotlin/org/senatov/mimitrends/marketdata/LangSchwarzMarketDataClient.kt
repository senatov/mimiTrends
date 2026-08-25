package org.senatov.mimitrends.marketdata

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
    val previousClose: Double?,
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
        val html = response.body()
        if (Jsoup.parse(html).select("[field=bidWithCurrencySymbol]").size < MIN_LISTINGS_PER_PAGE) {
            throw ProviderDataUnavailableException("Lang & Schwarz page structure changed")
        }
        parseListings(html)
    }.distinctBy(LangSchwarzListing::itemId)

    internal fun parseListings(
        html: String,
        date: LocalDate = LocalDate.now(QUOTE_ZONE),
        nowMillis: Long = System.currentTimeMillis()
    ): List<LangSchwarzListing> = Jsoup.parse(html).select("tr").mapNotNull { row ->
        parseRow(row, date, nowMillis)
    }.distinctBy(LangSchwarzListing::itemId)

    private fun parseRow(row: Element, date: LocalDate, nowMillis: Long): LangSchwarzListing? {
        val wkn = row.selectFirst("td:nth-child(1)")?.text()?.trim()?.uppercase().orEmpty()
        val link = row.selectFirst("td:nth-child(2) a[href]") ?: return null
        val bidElement = row.selectFirst("[field=bidWithCurrencySymbol]") ?: return null
        val askElement = row.selectFirst("[field=askWithCurrencySymbol]") ?: return null
        val timeText = row.selectFirst("[field=midTime]")?.text()?.trim() ?: return null
        val itemId = bidElement.attr("item").substringBefore('@').takeIf(ITEM_ID::matches) ?: return null
        val bid = germanNumber(bidElement.text()) ?: return null
        val ask = germanNumber(askElement.text()) ?: return null
        if (wkn.isBlank() || bid <= 0.0 || ask < bid) return null
        val time = runCatching { LocalTime.parse(timeText) }.getOrNull() ?: return null
        val observedAtMillis = inferFreshObservation(date, time, nowMillis) ?: return null
        val midpoint = (bid + ask) / 2.0
        val dailyChange = row.selectFirst("[field=midPerf1dWithCurrencySymbol]")
            ?.text()?.let(::germanNumber)
        return LangSchwarzListing(
            itemId = itemId,
            wkn = wkn,
            path = link.attr("href"),
            name = link.text().trim(),
            bid = bid,
            ask = ask,
            previousClose = dailyChange?.let(midpoint::minus)?.takeIf { it > 0.0 },
            observedAtMillis = observedAtMillis
        )
    }

    private fun inferFreshObservation(date: LocalDate, time: LocalTime, nowMillis: Long): Long? {
        var candidate = LocalDateTime.of(date, time).atZone(QUOTE_ZONE).toInstant().toEpochMilli()
        if (candidate > nowMillis + FUTURE_TOLERANCE_MILLIS) candidate -= Duration.ofDays(1).toMillis()
        val age = nowMillis - candidate
        return candidate.takeIf { age in -FUTURE_TOLERANCE_MILLIS..MAX_QUOTE_AGE_MILLIS }
    }

    private fun germanNumber(value: String): Double? = value
        .replace(".", "")
        .replace(",", ".")
        .replace(Regex("[^0-9.+-]"), "")
        .toDoubleOrNull()

    private companion object {
        val URLS = listOf(
            "https://www.ls-tc.de/de/aktien/deutschland",
            "https://www.ls-tc.de/de/aktien/europa/stoxx50",
            "https://www.ls-tc.de/de/aktien/europa"
        )
        const val USER_AGENT = "MiMiTrends/1.0 (personal desktop market viewer)"
        val QUOTE_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        const val FUTURE_TOLERANCE_MILLIS = 30_000L
        const val MAX_QUOTE_AGE_MILLIS = 10 * 60_000L
        const val MIN_LISTINGS_PER_PAGE = 10
        val ITEM_ID = Regex("[0-9]+")
    }
}
