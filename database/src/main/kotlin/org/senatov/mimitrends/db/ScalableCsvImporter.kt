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

    fun parse(path: Path, zoneId: ZoneId = ZoneId.systemDefault()): List<BrokerTransaction> {
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
                    status = value("status"), description = value("description"), assetType = value("assetType"),
                    type = value("type"), isin = value("isin").ifBlank { null }, shares = decimal(value("shares"), index),
                    price = decimal(value("price"), index), amount = decimal(value("amount"), index),
                    fee = decimal(value("fee"), index), tax = decimal(value("tax"), index), currency = value("currency")
                )
            }.toList()
        }
    }

    internal fun isCancelled(status: String): Boolean =
        status.trim().equals("Cancelled", ignoreCase = true) || status.trim().equals("Cancel", ignoreCase = true)

    private fun decimal(value: String, zeroBasedRow: Int): Double = runCatching {
        if (value.isBlank()) 0.0 else value.replace(".", "").replace(',', '.').toDouble()
    }.getOrElse { error("Invalid number '$value' on Scalable CSV row ${zeroBasedRow + 2}") }

    internal fun parseLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when (char) {
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
