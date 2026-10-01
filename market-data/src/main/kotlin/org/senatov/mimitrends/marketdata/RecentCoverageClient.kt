package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory
import org.jsoup.Jsoup
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class CoverageItem(
    val title: String,
    val publisher: String,
    val url: URI,
    val publishedAt: Instant?,
    val publishedLabel: String? = null
)

data class CoverageResult(val items: List<CoverageItem>, val unavailableSources: List<String>)

class RecentCoverageClient(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val mapper: ObjectMapper = ObjectMapper()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun load(symbol: String, companyName: String): CoverageResult {
        val items = mutableListOf<CoverageItem>()
        val unavailable = mutableListOf<String>()
        val sources = listOf(
            "Yahoo Finance" to { yahooNews(symbol, companyName) },
            "Benzinga" to { benzingaNews(symbol, companyName) },
            "wallstreetONLINE" to { wallstreetOnlineNews(symbol, companyName) }
        )
        sources.forEach { (name, fetch) ->
            runCatching(fetch).onSuccess(items::addAll).onFailure { error ->
                unavailable += name
                log.warn(LogTag.API, "RecentCoverageClient load failed source={} symbol={}", name, symbol, error)
            }
        }
        return CoverageResult(
            items.distinctBy { it.url.toString().substringBefore('?') }
                .sortedWith(compareByDescending<CoverageItem> { it.publishedAt ?: Instant.EPOCH })
                .take(20),
            unavailable
        )
    }

    private fun yahooNews(symbol: String, companyName: String): List<CoverageItem> {
        val tickerResult = runCatching { yahooSearch(symbol) }
        val tickerItems = tickerResult.getOrDefault(emptyList())
        val name = searchName(companyName)
        if (tickerItems.size >= 5 || name.equals(symbol, ignoreCase = true)) return tickerResult.getOrThrow()
        val nameResult = runCatching { yahooSearch(name).filter { matchesCompany(it.title, symbol, name) } }
        if (tickerResult.isFailure && nameResult.isFailure) throw tickerResult.exceptionOrNull()!!
        return tickerItems + nameResult.getOrDefault(emptyList())
    }

    private fun yahooSearch(query: String): List<CoverageItem> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val uri = URI.create("https://query1.finance.yahoo.com/v1/finance/search?q=$encoded&quotesCount=0&newsCount=20")
        return parseYahoo(request(uri))
    }

    private fun benzingaNews(symbol: String, companyName: String): List<CoverageItem> =
        parseBenzinga(request(URI.create("https://www.benzinga.com/feed")), symbol, companyName)

    private fun wallstreetOnlineNews(symbol: String, companyName: String): List<CoverageItem> {
        val slug = searchName(companyName).lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val stockResult = if (slug.length >= 4 && slug != symbol.lowercase(Locale.ROOT)) {
            runCatching {
                val url = URI.create("https://www.wallstreet-online.de/aktien/$slug-aktie/nachrichten")
                parseWallstreetOnlineStockNews(request(url), symbol, companyName)
            }
        } else Result.success(emptyList())
        val feedResult = runCatching {
            parseWallstreetOnline(
                request(URI.create("https://www.wallstreet-online.de/rss/nachrichten-alle.xml")),
                symbol, companyName
            )
        }
        if (stockResult.isFailure && feedResult.isFailure) throw stockResult.exceptionOrNull()!!
        return stockResult.getOrDefault(emptyList()) + feedResult.getOrDefault(emptyList())
    }

    private fun request(uri: URI): String {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(8))
            .header("User-Agent", "Mozilla/5.0 MiMiTrends")
            .GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        check(response.statusCode() == 200) { "News source HTTP ${response.statusCode()}" }
        return response.body()
    }

    internal fun parseYahoo(body: String): List<CoverageItem> =
        mapper.readTree(body).path("news").mapNotNull { entry ->
            val title = entry.path("title").asText().trim()
            val publisher = entry.path("publisher").asText().trim()
            val url = safeUri(entry.path("link").asText())
            if (title.isEmpty() || url == null) null else CoverageItem(
                title, publisher.ifEmpty { "Yahoo Finance" }, url,
                entry.path("providerPublishTime").takeIf { it.isNumber }?.asLong()?.let(Instant::ofEpochSecond)
            )
        }

    internal fun parseBenzinga(body: String, symbol: String, companyName: String): List<CoverageItem> {
        return parseRss(body, symbol, companyName, "Benzinga", "pubDate")
    }

    internal fun parseWallstreetOnline(body: String, symbol: String, companyName: String): List<CoverageItem> =
        parseRss(body, symbol, companyName, "wallstreetONLINE", "dc:date")

    internal fun parseWallstreetOnlineStockNews(body: String, symbol: String, companyName: String): List<CoverageItem> {
        val document = Jsoup.parse(body, "https://www.wallstreet-online.de")
        if (!matchesCompany(document.selectFirst("h1")?.text().orEmpty(), symbol, companyName)) return emptyList()
        val latest = document.selectFirst(".tabpanes .tab") ?: return emptyList()
        return latest.select(".newsListSubtitle").take(30).mapNotNull { subtitle ->
            val row = subtitle.closest("tr") ?: return@mapNotNull null
            val headline = row.selectFirst(".fw-semibold a[href^=/nachricht/]") ?: return@mapNotNull null
            val title = headline.text().trim()
            val url = safeUri(headline.absUrl("href")) ?: return@mapNotNull null
            val details = subtitle.text().split('·').map(String::trim)
            val dateText = details.firstOrNull().orEmpty()
            val date = runCatching { LocalDate.parse(dateText, DateTimeFormatter.ofPattern("dd.MM.yy")) }
                .getOrNull()
            CoverageItem(
                title, details.getOrNull(1).orEmpty().ifBlank { "wallstreetONLINE" }, url,
                date?.atStartOfDay(ZoneId.of("Europe/Berlin"))?.toInstant(),
                date?.toString()
            )
        }
    }

    private fun parseRss(
        body: String, symbol: String, companyName: String, publisher: String, dateField: String
    ): List<CoverageItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(body)))
        val nodes = document.getElementsByTagName("item")
        return (0 until nodes.length.coerceAtMost(100)).mapNotNull { index ->
            val item = nodes.item(index)
            fun field(name: String): String = (0 until item.childNodes.length)
                .map(item.childNodes::item)
                .firstOrNull { it.nodeName == name }?.textContent?.trim().orEmpty()
            val title = field("title")
            if (title.isEmpty() || !matchesCompany("$title ${field("description")}", symbol, companyName)) {
                return@mapNotNull null
            }
            val url = safeUri(field("link")) ?: return@mapNotNull null
            val published = if (dateField == "dc:date") {
                runCatching { OffsetDateTime.parse(field(dateField)).toInstant() }.getOrNull()
            } else {
                runCatching { ZonedDateTime.parse(field(dateField), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
                    .getOrNull()
            }
            CoverageItem(title, publisher, url, published)
        }
    }

    internal fun searchName(companyName: String): String = companyName.trim()
        .replace(Regex("(?i)\\s+(inc\\.?|corp\\.?|corporation|ag|se|plc|ltd\\.?)$"), "")

    internal fun matchesCompany(text: String, symbol: String, companyName: String): Boolean {
        val name = searchName(companyName)
        val terms = listOf(name, symbol.substringBefore('.'))
            .map(String::trim).filter { it.length >= 4 }
        return terms.any { term ->
            Regex("(?i)(?<![\\p{Alnum}])${Regex.escape(term)}(?![\\p{Alnum}])").containsMatchIn(text)
        }
    }

    private fun safeUri(value: String): URI? = runCatching { URI.create(value) }
        .getOrNull()?.takeIf { it.scheme == "https" || it.scheme == "http" }
}
