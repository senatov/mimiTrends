package org.senatov.mimitrends

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

object ApiKeyResolver {
    fun resolve(): String? = resolveValue("FINNHUB_API_KEY")

    fun resolveWebhookSecret(): String? = resolveValue("FINNHUB_WEBHOOK_SECRET")

    fun saveLocal(apiKey: String, webhookSecret: String?) {
        val properties = Properties().apply {
            setProperty("FINNHUB_API_KEY", apiKey.trim())
            webhookSecret?.trim()?.takeIf(String::isNotEmpty)?.let {
                setProperty("FINNHUB_WEBHOOK_SECRET", it)
            }
        }
        Files.createDirectories(CONFIG_FILE.parent)
        Files.newOutputStream(CONFIG_FILE).use { output ->
            properties.store(output, "MiMiTrends local Finnhub credentials")
        }
        runCatching {
            Files.setPosixFilePermissions(
                CONFIG_FILE,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
    }

    private fun resolveValue(variableName: String): String? {
        val environmentValue = System.getenv(variableName)?.trim()
        if (!environmentValue.isNullOrEmpty()) return environmentValue

        readDotEnv(variableName)?.let { return it }
        return readProperties(variableName)
    }

    private fun readDotEnv(variableName: String): String? {
        val envFile = Path.of(".env")
        if (!Files.isRegularFile(envFile)) return null
        return Files.readAllLines(envFile)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val (name, value) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                value.trim().takeIf { name.trim() == variableName && it.isNotEmpty() }
            }
            .firstOrNull()
    }

    private fun readProperties(variableName: String): String? {
        if (!Files.isRegularFile(CONFIG_FILE)) return null
        val properties = Properties()
        Files.newInputStream(CONFIG_FILE).use(properties::load)
        return properties.getProperty(variableName)?.trim()?.takeIf(String::isNotEmpty)
    }

    val configFile: Path
        get() = CONFIG_FILE

    private val CONFIG_FILE: Path =
        Path.of(System.getProperty("user.home"), ".mimi", "trends", "finnhub.properties")
}
