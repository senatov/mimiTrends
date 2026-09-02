package org.senatov.mimitrends.db

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ScalableCsvImporter {
    private val requiredHeaders = setOf(
        "date", "time", "status", "reference", "description", "assetType", "type", "isin",
        "shares", "price", "amount", "fee", "tax", "currency"
    )

    fun parse(path: Path, zoneId: ZoneId = ZoneId.of("Europe/Berlin")): List<BrokerTransaction> {
        Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
            val headerLine = reader.readLine() ?: error("The selected CSV file is empty")
            val headers = parseLine(headerLine).map { it.removePrefix("\uFEFF").trim() }
            require(headers.toSet().containsAll(requiredHeaders)) {
                "This is not a supported Scalable transactions CSV (missing: ${requiredHeaders - headers.toSet()})"
            }
            val positions = headers.withIndex().associate { it.value to it.index }
            return reader.lineSequence().filter(String::isNotBlank).mapIndexedNotNull { index, line ->
                val values = parseLine(line)
                fun value(name: String): String = values.getOrNull(positions.getValue(name))?.trim().orEmpty()
                if (isCancelled(value("status"))) return@mapIndexedNotNull null
                val canonical = headers.joinToString("\u001f") { value(it) }
                BrokerTransaction(
                    source = "SCALABLE",
                    reference = value("reference").ifBlank { null },
                    fingerprint = sha256(canonical),
                    occurredAtEpochSeconds = runCatching {
                        LocalDateTime.parse("${value("date")} ${value("time")}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            .atZone(zoneId).toEpochSecond()
                    }.getOrElse { error("Invalid Scalable date/time on CSV row ${index + 2}") },
                    status = canonical(value("status")), description = value("description"),
                    assetType = canonical(value("assetType")), type = canonical(value("type")),
                    isin = value("isin").uppercase().ifBlank { null },
                    shares = decimal(value("shares"), index, "shares", nonNegative = true),
                    price = decimal(value("price"), index, "price", nonNegative = true),
                    amount = decimal(value("amount"), index, "amount"),
                    fee = decimal(value("fee"), index, "fee", nonNegative = true),
                    tax = decimal(value("tax"), index, "tax"),
                    currency = value("currency").uppercase()
                )
            }.distinctBy { transaction ->
                transaction.reference?.let { "reference:$it" } ?: "fingerprint:${transaction.fingerprint}"
            }.toList()
        }
    }

    internal fun isCancelled(status: String): Boolean =
        status.trim().equals("Cancelled", ignoreCase = true) || status.trim().equals("Cancel", ignoreCase = true)

    private fun canonical(value: String): String = value.trim().lowercase().replaceFirstChar(Char::uppercase)

    private fun decimal(
        value: String,
        zeroBasedRow: Int,
        field: String,
        nonNegative: Boolean = false
    ): Double = runCatching {
        if (value.isBlank()) 0.0 else value.replace(".", "").replace(',', '.').toDouble()
    }.getOrElse { error("Invalid $field '$value' on Scalable CSV row ${zeroBasedRow + 2}") }.also { parsed ->
        require(parsed.isFinite() && (!nonNegative || parsed >= 0.0)) {
            "Invalid $field '$value' on Scalable CSV row ${zeroBasedRow + 2}"
        }
    }

    internal fun parseLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            when (val char = line[index]) {
                '"' -> if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                    field.append('"'); index++
                } else quoted = !quoted
                ';' -> if (!quoted) { result += field.toString(); field.setLength(0) } else field.append(char)
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "Unclosed quoted field in CSV" }
        result += field.toString()
        return result
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}