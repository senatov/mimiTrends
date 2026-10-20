package org.senatov.mimitrends.marketdata

import java.net.URI
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

enum class ProviderUrl(val label: String, val default: String, val probePath: String) {
    FINNHUB_REST("Finnhub · company profiles", "https://finnhub.io", ""),
    FINNHUB_STREAM("Finnhub · live trades", "wss://ws.finnhub.io", ""),
    EURONEXT("Euronext", "https://live.euronext.com", "/en/instrumentSearch/searchJSON?q=ASML"),
    TRADEGATE("Tradegate", "https://www.tradegatebsx.com", "/kurssuche.php?suche=ASML&lang=en"),
    LANG_SCHWARZ_GERMANY("Lang & Schwarz · Germany", "https://www.ls-tc.de/de/aktien/deutschland", ""),
    LANG_SCHWARZ_STOXX("Lang & Schwarz · STOXX 50", "https://www.ls-tc.de/de/aktien/europa/stoxx50", ""),
    LANG_SCHWARZ_EUROPE("Lang & Schwarz · Europe", "https://www.ls-tc.de/de/aktien/europa", ""),
    YAHOO_PRIMARY("Yahoo · primary", "https://query1.finance.yahoo.com", "/v1/finance/search?q=ASML&quotesCount=1&newsCount=0"),
    YAHOO_ALTERNATE("Yahoo · alternate", "https://query2.finance.yahoo.com", "/v1/finance/search?q=ASML&quotesCount=1&newsCount=0"),
    WALLSTREET_ONLINE("wallstreetONLINE", "https://www.wallstreet-online.de", "/suche/?suche=&q=ASML&sa=Suche"),
    TRADERFOX("TraderFox", "https://markets.traderfox.com/aktien/", "dax-bestandteile")
}

object ProviderUrls {
    private val store = ProviderUrlStore(Path.of(System.getProperty("user.home"), ".mimi", "trends",
        "provider-urls.properties"))

    fun get(key: ProviderUrl): String = store.get(key)

    fun validate(key: ProviderUrl, value: String): String {
        val uri = URI.create(value.trim())
        val scheme = if (key == ProviderUrl.FINNHUB_STREAM) "wss" else "https"
        require(uri.scheme.equals(scheme, ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.rawQuery == null) {
            "Enter a $scheme URL without a query or credentials"
        }
        return if (key == ProviderUrl.TRADERFOX) value.trim().trimEnd('/') + "/"
        else value.trim().trimEnd('/')
    }

    fun save(updated: Map<ProviderUrl, String>) = store.save(updated)
}

internal class ProviderUrlStore(private val path: Path) {
    @Volatile private var values: Map<ProviderUrl, String> = load()

    fun get(key: ProviderUrl): String = values[key] ?: key.default

    @Synchronized fun save(updated: Map<ProviderUrl, String>) {
        val normalized = ProviderUrl.entries.associateWith { ProviderUrls.validate(it, updated[it] ?: get(it)) }
        Files.createDirectories(path.parent)
        val properties = Properties()
        normalized.forEach { (key, url) -> properties.setProperty(key.name, url) }
        val temp = Files.createTempFile(path.parent, "provider-urls-", ".tmp")
        try {
            Files.newOutputStream(temp).use { properties.store(it, "Market data provider URLs") }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        values = normalized
    }

    private fun load(): Map<ProviderUrl, String> = runCatching {
        if (!Files.exists(path)) emptyMap() else {
            val properties = Properties().also { Files.newInputStream(path).use(it::load) }
            ProviderUrl.entries.mapNotNull { key ->
                properties.getProperty(key.name)?.let { value ->
                    runCatching { key to ProviderUrls.validate(key, value) }.getOrNull()
                }
            }.toMap()
        }
    }.getOrDefault(emptyMap())
}
