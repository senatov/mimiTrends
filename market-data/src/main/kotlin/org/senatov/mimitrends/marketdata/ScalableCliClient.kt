package org.senatov.mimitrends.marketdata

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ScalableQuote(
    val isin: String,
    val name: String,
    val currency: String,
    val midpoint: Double,
    val bid: Double?,
    val ask: Double?,
    val previousClose: Double?,
    val observedAtMillis: Long
)

class ScalableCliUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

interface ScalableQuoteClient {
    fun verifyAccess()
    fun loadQuote(isin: String): ScalableQuote
}

class ScalableCliClient(
    private val mapper: ObjectMapper = ObjectMapper(),
    private val commandRunner: (List<String>) -> String = ScalableCliCommandRunner()::run
) : ScalableQuoteClient {
    override fun verifyAccess() {
        val root = parse(commandRunner(listOf("capabilities", "--json")))
        if (!root.path("ok").asBoolean(false)) throw ScalableCliUnavailableException("Scalable CLI access unavailable")
    }

    override fun loadQuote(isin: String): ScalableQuote {
        val root = parse(commandRunner(listOf("broker", "quote", "--isin", isin, "--json")))
        if (!root.path("ok").asBoolean(false)) throw ScalableCliUnavailableException("Scalable quote unavailable")
        val result = root.path("data").path("result")
        val midpoint = result.path("quote_mid_price").asDouble(Double.NaN)
        val timestamp = result.path("quote_timestamp_utc").asText("")
        if (!midpoint.isFinite() || midpoint <= 0.0 || timestamp.isBlank()) {
            throw ScalableCliUnavailableException("Scalable quote response is incomplete")
        }
        val intradayReturn = result.path("quote_performances")
            .firstOrNull { it.path("timeframe").asText() == "INTRADAY" }
            ?.path("simple_absolute_return")
            ?.takeUnless { it.isMissingNode || it.isNull }
            ?.asDouble()
        return ScalableQuote(
            isin = result.path("isin").asText(isin),
            name = result.path("name").asText(""),
            currency = result.path("quote_currency").asText("EUR"),
            midpoint = midpoint,
            bid = result.optionalDouble("quote_bid_price"),
            ask = result.optionalDouble("quote_ask_price"),
            previousClose = intradayReturn?.let(midpoint::minus),
            observedAtMillis = Instant.parse(timestamp).toEpochMilli()
        )
    }

    private fun parse(json: String) = runCatching { mapper.readTree(json) }
        .getOrElse { throw ScalableCliUnavailableException("Invalid Scalable CLI response", it) }

    private fun com.fasterxml.jackson.databind.JsonNode.optionalDouble(field: String): Double? =
        path(field).takeUnless { it.isMissingNode || it.isNull }?.asDouble()?.takeIf(Double::isFinite)
}

internal class ScalableCliCommandRunner(
    private val executable: String = findExecutable(),
    private val timeoutSeconds: Long = 5
) {
    fun run(arguments: List<String>): String {
        val process = runCatching {
            ProcessBuilder(listOf(executable) + arguments)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { throw ScalableCliUnavailableException("Scalable CLI is not installed", it) }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw ScalableCliUnavailableException("Scalable CLI request timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0) {
            throw ScalableCliUnavailableException("Scalable CLI is not authorized")
        }
        return output
    }

    private companion object {
        fun findExecutable(): String {
            val pathCandidates = System.getenv("PATH").orEmpty()
                .split(File.pathSeparator)
                .filter(String::isNotBlank)
                .map { File(it, if (isWindows()) "sc.exe" else "sc") }
            val candidates = buildList {
                if (isWindows()) add(File(System.getenv("LOCALAPPDATA").orEmpty(), "Scalable Capital/sc.exe"))
                else {
                    add(File("/opt/homebrew/bin/sc"))
                    add(File("/usr/local/bin/sc"))
                }
                addAll(pathCandidates)
            }
            return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: "sc"
        }

        fun isWindows(): Boolean = System.getProperty("os.name").contains("win", ignoreCase = true)
    }
}
