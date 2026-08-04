package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EuronextInstrument(val isin: String, val mic: String, val name: String)

data class EuronextQuote(
    val last: Double,
    val bid: Double?,
    val ask: Double?,
    val currency: String,
    val observedAtMillis: Long
)

class EuronextMarketDataClient(
    private val mapper: ObjectMapper = ObjectMapper(),
    cookieManager: CookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL),
    private val client: HttpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
) {
    fun resolveInstrument(queryText: String): EuronextInstrument? {
        val query = URLEncoder.encode(queryText.trim(), StandardCharsets.UTF_8)
        val response = send("$BASE_URL/en/instrumentSearch/searchJSON?q=$query")
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "Euronext instrument search")
        }
        val choices = mapper.readTree(response.body()).mapNotNull { node ->
            val isin = node.path("isin").asText()
            val mic = node.path("mic").asText()
            val name = node.path("name").asText()
            if (isin.matches(VALID_ISIN) && mic.matches(VALID_MIC)) EuronextInstrument(isin, mic, name) else null
        }
        return choices.minByOrNull { MIC_PRIORITY.indexOf(it.mic).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE }
    }

    fun loadQuote(instrument: EuronextInstrument): EuronextQuote {
        require(instrument.isin.matches(VALID_ISIN) && instrument.mic.matches(VALID_MIC))
        val product = "${instrument.isin}-${instrument.mic}"
        val response = send("$BASE_URL/en/ajax/getDetailedQuote/$product", "$BASE_URL/en/product/equities/$product/market-information")
        if (response.statusCode() != 200) {
            throw ProviderHttpException.from(response.statusCode(), response.headers(), "Euronext quote for $product")
        }
        val envelope = mapper.readTree(response.body())
        val html = decryptEnvelope(
            envelope.path("ct").asText(), envelope.path("iv").asText(), envelope.path("s").asText(), PASSWORD
        )
        return parseQuote(html)
    }

    internal fun parseQuote(html: String): EuronextQuote {
        val last = elementText(html, "header-instrument-price")?.decimal()
            ?: throw ProviderDataUnavailableException("Euronext returned no last traded price")
        val currency = when (elementText(html, "header-instrument-currency")?.trim()) {
            "€", "EUR" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
        val bid = labeledValue(html, "Best Bid")
        val ask = labeledValue(html, "Best Ask")
        val timestamp = LAST_TRADE_TIME.find(html)?.groupValues?.get(1)?.replace("&nbsp;", " ")?.trim()
        val observedAt = timestamp?.let {
            runCatching { LocalDateTime.parse(it, QUOTE_TIME).atZone(EURONEXT_ZONE).toInstant().toEpochMilli() }.getOrNull()
        } ?: System.currentTimeMillis()
        return EuronextQuote(last, bid, ask, currency, observedAt)
    }

    internal fun decryptEnvelope(ciphertext: String, ivHex: String, saltHex: String, password: String): String {
        require(ciphertext.isNotBlank() && ivHex.isNotBlank() && saltHex.isNotBlank()) { "Invalid Euronext envelope" }
        val salt = saltHex.hexBytes()
        val key = evpBytesToKey(password.toByteArray(StandardCharsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivHex.hexBytes()))
        val jsonString = String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8)
        return mapper.readTree(jsonString).asText()
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray): ByteArray {
        val output = ArrayList<Byte>(AES_KEY_BYTES)
        var previous = ByteArray(0)
        while (output.size < AES_KEY_BYTES) {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(previous)
            digest.update(password)
            digest.update(salt)
            previous = digest.digest()
            previous.forEach(output::add)
        }
        return output.take(AES_KEY_BYTES).toByteArray()
    }

    private fun send(url: String, referer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
            .header("User-Agent", WINDOWS_USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
        referer?.let { builder.header("Referer", it).header("X-Requested-With", "XMLHttpRequest") }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun elementText(html: String, id: String): String? =
        Regex("id=[\"']${Regex.escape(id)}[\"'][^>]*>([^<]+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.htmlText()

    private fun labeledValue(html: String, label: String): Double? =
        Regex(">${Regex.escape(label)}<.*?<span[^>]*>([^<]+)</span>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.htmlText()?.decimal()

    private fun String.decimal(): Double? {
        val compact = replace(" ", "").replace("\u00A0", "")
        val normalized = when {
            ',' in compact && '.' in compact && compact.lastIndexOf('.') > compact.lastIndexOf(',') -> compact.replace(",", "")
            ',' in compact && '.' in compact -> compact.replace(".", "").replace(',', '.')
            ',' in compact && compact.substringAfterLast(',').length == 3 -> compact.replace(",", "")
            else -> compact.replace(',', '.')
        }
        return normalized.toDoubleOrNull()?.takeIf(Double::isFinite)
    }
    private fun String.htmlText(): String = replace("&nbsp;", " ").replace("&euro;", "€").trim()
    private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val BASE_URL = "https://live.euronext.com"
        const val PASSWORD = "24ayqVo7yJma"
        const val AES_KEY_BYTES = 32
        const val WINDOWS_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val VALID_ISIN = Regex("[A-Z]{2}[A-Z0-9]{9}[0-9]")
        val VALID_MIC = Regex("[A-Z0-9]{4}")
        val MIC_PRIORITY = listOf("XPAR", "XAMS", "XBRU", "XLIS", "XOSL", "ETLX", "MTAH", "BGEM")
        val LAST_TRADE_TIME = Regex("last-price-date-time[^>]*>\\s*([0-9]{2}/[0-9]{2}/[0-9]{4}\\s*-\\s*[0-9]{2}:[0-9]{2})")
        val QUOTE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm")
        val EURONEXT_ZONE: ZoneId = ZoneId.of("Europe/Paris")
    }
}
