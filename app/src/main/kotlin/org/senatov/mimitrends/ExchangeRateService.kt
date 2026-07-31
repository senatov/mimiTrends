package org.senatov.mimitrends

import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CompletableFuture

class ExchangeRateService(private val cachePath: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "exchange-rate.properties")) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Volatile private var usdPerEur = loadCached() ?: 1.0

    fun usdToEur(value: Double): Double {
        log.trace(LogTag.STATE, "usdToEur(value={})", value)
        return value / usdPerEur
    }

    fun eurToUsd(value: Double): Double {
        log.trace(LogTag.STATE, "eurToUsd(value={})", value)
        return value * usdPerEur
    }

    fun refresh(): CompletableFuture<Double> {
        log.debug(LogTag.API, "refresh()")
        val request = HttpRequest.newBuilder(URI.create("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"))
            .timeout(Duration.ofSeconds(8)).GET().build()
        return HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply { response ->
            require(response.statusCode() == 200) { "ECB exchange-rate HTTP ${response.statusCode()}" }
            val rate = Regex("currency=['\"]USD['\"]\\s+rate=['\"]([0-9.]+)").find(response.body())?.groupValues?.get(1)?.toDouble()
                ?: error("USD rate is missing in ECB response")
            usdPerEur = rate; save(rate); log.info(LogTag.API, "ECB exchange rate loaded EUR/USD={}", rate); rate
        }
    }

    private fun loadCached(): Double? {
        log.debug(LogTag.IO, "loadCached(path={})", cachePath)
        if (!Files.exists(cachePath)) return null
        return runCatching { Properties().also { Files.newInputStream(cachePath).use(it::load) }.getProperty("usdPerEur")?.toDouble() }.getOrNull()
    }

    private fun save(rate: Double) {
        log.debug(LogTag.IO, "save(rate={})", rate)
        Files.createDirectories(cachePath.parent)
        Files.newOutputStream(cachePath).use { Properties().apply { setProperty("usdPerEur", rate.toString()) }.store(it, "ECB reference rate") }
    }
}
