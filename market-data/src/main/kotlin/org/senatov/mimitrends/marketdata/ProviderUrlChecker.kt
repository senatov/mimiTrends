package org.senatov.mimitrends.marketdata

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ProviderUrlChecker(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun check(key: ProviderUrl, candidate: String, apiKey: String? = null): String {
        val base = ProviderUrls.validate(key, candidate)
        if (key == ProviderUrl.FINNHUB_REST || key == ProviderUrl.FINNHUB_STREAM) {
            val token = apiKey?.takeIf(String::isNotBlank)
                ?: throw ProviderDataUnavailableException("Configure a Finnhub API key to check this URL")
            val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8)
            if (key == ProviderUrl.FINNHUB_STREAM) {
                try {
                    val socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
                        .buildAsync(URI.create("$base?token=$encodedToken"), object : java.net.http.WebSocket.Listener {})
                        .get(12, TimeUnit.SECONDS)
                    socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Check complete")
                } catch (_: Exception) {
                    throw ProviderDataUnavailableException("Finnhub stream connection failed; check URL and API key")
                }
                return "Live stream connected"
            }
            val request = HttpRequest.newBuilder(URI.create("$base/api/v1/stock/profile2?symbol=ASML&token=$encodedToken"))
                .timeout(Duration.ofSeconds(12)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200 || !response.body().contains("\"name\"")) {
                throw ProviderDataUnavailableException("Finnhub profile check failed (HTTP ${response.statusCode()})")
            }
            return "Company profile OK"
        }
        if (key == ProviderUrl.EURONEXT) {
            val provider = EuronextMarketDataClient(baseUrlProvider = { base })
            val instrument = provider.resolveInstrument("ASML", "NL0010273215")
                ?: throw ProviderDataUnavailableException("ASML equity was not found")
            val quote = provider.loadQuote(instrument)
            require(quote.last > 0 && quote.observedAtMillis > 0) { "No valid Euronext quote" }
            return "Search and ASML quote OK"
        }
        if (key == ProviderUrl.TRADEGATE) {
            val provider = TradegateMarketDataClient(baseUrlProvider = { base })
            val instrument = provider.resolveInstrument("SAP.DE", "SAP SE")
                ?: throw ProviderDataUnavailableException("SAP equity was not found")
            val quote = provider.loadQuote(instrument.isin)
            require(quote.bid != null && quote.ask != null && quote.ask >= quote.bid) {
                "SAP bid/ask were unavailable"
            }
            return "Search and SAP quote OK"
        }
        val uri = URI.create(base + key.probePath)
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
            .header("User-Agent", "Mozilla/5.0 MiMiTrends")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), key.label)
        }
        val body = response.body()
        val relevant = when (key) {
            ProviderUrl.FINNHUB_REST, ProviderUrl.FINNHUB_STREAM -> true
            ProviderUrl.TRADEGATE -> body.contains("ASML", ignoreCase = true)
            ProviderUrl.LANG_SCHWARZ_GERMANY,
            ProviderUrl.LANG_SCHWARZ_STOXX,
            ProviderUrl.LANG_SCHWARZ_EUROPE -> body.contains("bidWithCurrencySymbol")
            ProviderUrl.YAHOO_PRIMARY, ProviderUrl.YAHOO_ALTERNATE -> body.contains("\"quotes\"")
            ProviderUrl.WALLSTREET_ONLINE -> body.contains("ASML", ignoreCase = true)
            ProviderUrl.TRADERFOX -> body.contains("equities")
            ProviderUrl.EURONEXT -> true
        }
        if (!relevant) throw ProviderDataUnavailableException("Page responded, but expected market data was missing")
        return "Market data OK"
    }
}
