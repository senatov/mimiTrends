package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.DisplayCurrency
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

class ScannerSettingsService(private val path: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "scanner.properties")) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun load(): ScannerCriteria {
        log.debug(LogTag.IO, "load(path={})", path)
        if (!Files.exists(path)) return ScannerCriteria()
        return runCatching {
            val p = Properties().also { Files.newInputStream(path).use(it::load) }
            ScannerCriteria(
                minRelativeVolume = p.getProperty("minRelativeVolume", "0.0").toDouble(),
                minChange1mPercent = p.getProperty("minChange1mPercent", "-0.10").toDouble(),
                minChange5mPercent = p.getProperty("minChange5mPercent", "-0.25").toDouble(),
                minPrice = p.getProperty("minPrice", "8.0").toDouble(),
                minSessionVolume = p.getProperty("minSessionVolume", "20000").toDouble(),
                baselineSessions = p.getProperty("baselineSessions", "20").toInt(),
                batchSize = p.getProperty("batchSize", "50").toInt().coerceIn(1, 50),
                rotationSeconds = p.getProperty("rotationSeconds", "30").toLong().coerceIn(5, 3600),
                displayCurrency = runCatching { DisplayCurrency.valueOf(p.getProperty("displayCurrency", "EUR")) }.getOrDefault(DisplayCurrency.EUR),
                symbols = normalizeSymbols(p.getProperty("symbols", ScannerCriteria().symbols.joinToString(",")))
            )
        }.onFailure { log.error(LogTag.IO, "scanner settings load failed", it) }.getOrDefault(ScannerCriteria())
    }

    fun save(value: ScannerCriteria) {
        log.debug(LogTag.IO, "save(symbols={})", value.symbols.size)
        Files.createDirectories(path.parent)
        val p = Properties().apply {
            setProperty("minRelativeVolume", value.minRelativeVolume.toString()); setProperty("minChange1mPercent", value.minChange1mPercent.toString())
            setProperty("minChange5mPercent", value.minChange5mPercent.toString()); setProperty("minPrice", value.minPrice.toString())
            setProperty("minSessionVolume", value.minSessionVolume.toString()); setProperty("baselineSessions", value.baselineSessions.toString())
            setProperty("batchSize", value.batchSize.toString()); setProperty("rotationSeconds", value.rotationSeconds.toString())
            setProperty("displayCurrency", value.displayCurrency.name)
            setProperty("symbols", normalizeSymbols(value.symbols.joinToString(",")).joinToString(","))
        }
        Files.newOutputStream(path).use { p.store(it, "MiMiTrends scanner settings") }
    }

    fun normalizeSymbols(text: String): List<String> {
        log.debug(LogTag.IO, "normalizeSymbols(chars={})", text.length)
        return text.split(',', ';', '\n', ' ', '\t').map(String::trim).filter(String::isNotEmpty).map(String::uppercase).distinct()
    }
}
