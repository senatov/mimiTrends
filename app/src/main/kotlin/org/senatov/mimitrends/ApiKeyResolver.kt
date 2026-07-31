package org.senatov.mimitrends

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory

object ApiKeyResolver {
    private val log = LoggerFactory.getLogger(ApiKeyResolver::class.java)

    fun resolve(): String? {
        log.debug(LogTag.STATE, "resolve()")
        return resolveValue("FINNHUB_API_KEY")
    }

    fun resolveWebhookSecret(): String? {
        log.debug(LogTag.STATE, "resolveWebhookSecret()")
        return resolveValue("FINNHUB_WEBHOOK_SECRET")
    }

    fun saveLocal(apiKey: String, webhookSecret: String?) {
        log.debug(LogTag.STATE, "saveLocal(apiKeyPresent={}, webhookPresent={})", apiKey.isNotBlank(), !webhookSecret.isNullOrBlank())
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
        log.info(LogTag.STATE, "credentials saved path={}", CONFIG_FILE)
    }

    private fun resolveValue(variableName: String): String? {
        log.debug(LogTag.STATE, "resolveValue(variableName={})", variableName)
        val environmentValue = System.getenv(variableName)?.trim()
        if (!environmentValue.isNullOrEmpty()) {
            log.debug(LogTag.STATE, "credential found source=environment name={}", variableName)
            return environmentValue
        }

        readDotEnv(variableName)?.let { return it }
        return readProperties(variableName)
    }

    private fun readDotEnv(variableName: String): String? {
        log.debug(LogTag.IO, "readDotEnv(variableName={})", variableName)
        val envFile = generateSequence(Path.of("").toAbsolutePath()) { current -> current.parent }
            .take(4)
            .map { directory -> directory.resolve(".env") }
            .firstOrNull(Files::isRegularFile)
            ?: return null
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
            .also { log.debug(LogTag.IO, "dot-env lookup name={} found={} path={}", variableName, it != null, envFile) }
    }

    private fun readProperties(variableName: String): String? {
        log.debug(LogTag.IO, "readProperties(variableName={})", variableName)
        if (!Files.isRegularFile(CONFIG_FILE)) return null
        val properties = Properties()
        Files.newInputStream(CONFIG_FILE).use(properties::load)
        return properties.getProperty(variableName)?.trim()?.takeIf(String::isNotEmpty)
            .also { log.debug(LogTag.IO, "properties lookup name={} found={}", variableName, it != null) }
    }

    val configFile: Path
        get() = CONFIG_FILE

    private val CONFIG_FILE: Path =
        Path.of(System.getProperty("user.home"), ".mimi", "trends", "finnhub.properties")
}
