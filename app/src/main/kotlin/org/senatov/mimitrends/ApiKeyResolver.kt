package org.senatov.mimitrends

import java.nio.file.Files
import java.nio.file.Path

object ApiKeyResolver {
    fun resolve(): String? {
        val environmentKey = System.getenv("FINNHUB_API_KEY")?.trim()
        if (!environmentKey.isNullOrEmpty()) return environmentKey

        val envFile = Path.of(".env")
        if (!Files.isRegularFile(envFile)) return null
        return Files.readAllLines(envFile)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val (name, value) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                value.trim().takeIf { name.trim() == "FINNHUB_API_KEY" && it.isNotEmpty() }
            }
            .firstOrNull()
    }
}
