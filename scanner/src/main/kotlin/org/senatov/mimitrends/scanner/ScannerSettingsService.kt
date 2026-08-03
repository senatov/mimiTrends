package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.TableAppearance
import org.senatov.mimitrends.model.AnomalyWindow
import org.senatov.mimitrends.model.MarketRegion
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
                anomalyWindow = enumValue(p.getProperty("anomalyWindow"), AnomalyWindow.HOUR),
                marketRegion = enumValue(p.getProperty("marketRegion"), MarketRegion.BOTH),
                scanIntervalSeconds = p.getProperty("scanIntervalSeconds", "180").toLong().coerceIn(60, 3_600),
                resultLimit = p.getProperty("resultLimit", "15").toInt().coerceIn(5, 15),
                minPrice = p.getProperty("minPrice", "2.0").toDouble(),
                minSessionTurnover = p.getProperty("minSessionTurnover", "0").toDouble(),
                baselineSessions = p.getProperty("baselineSessions", "5").toInt().coerceIn(3, 20),
                maxSignalAgeMinutes = p.getProperty("maxSignalAgeMinutes", "2").toInt().coerceIn(0, 5),
                minJumpZ = p.getProperty("minJumpZ", "3.0").toDouble().coerceIn(1.0, 20.0),
                minRangeZ = p.getProperty("minRangeZ", "3.5").toDouble().coerceIn(1.0, 20.0),
                minVolumeZ = p.getProperty("minVolumeZ", "2.0").toDouble().coerceIn(0.0, 20.0),
                minRelativeVolume = p.getProperty("minRelativeVolume", "1.8").toDouble().coerceIn(0.0, 20.0),
                minBodyRatio = p.getProperty("minBodyRatio", "0.55").toDouble().coerceIn(0.0, 1.0),
                minAbsoluteMovePercent = p.getProperty("minAbsoluteMovePercent", "0.20").toDouble().coerceIn(0.0, 10.0),
                minimumTableResults = p.getProperty("minimumTableResults", "10").toInt().coerceIn(5, 15),
                trendWindowMinutes = p.getProperty("trendWindowMinutes", "180").toInt().coerceIn(60, 360),
                minTrendReturnPercent = p.getProperty("minTrendReturnPercent", "0.45").toDouble().coerceIn(0.1, 20.0),
                minTrendEfficiency = p.getProperty("minTrendEfficiency", "0.08").toDouble().coerceIn(0.01, 1.0),
                displayCurrency = runCatching { DisplayCurrency.valueOf(p.getProperty("displayCurrency", "EUR")) }.getOrDefault(DisplayCurrency.EUR),
                tableAppearance = TableAppearance(
                    fontFamily = p.getProperty("table.fontFamily", "SF Pro Display"),
                    fontSize = p.getProperty("table.fontSize", "12.0").toDouble().coerceIn(9.0, 22.0),
                    textColor = color(p.getProperty("table.textColor"), "#263238"),
                    evenRowColor = color(p.getProperty("table.evenRowColor"), "#FAFAFA"),
                    oddRowColor = color(p.getProperty("table.oddRowColor"), "#F0F0F0"),
                    selectionColor = color(p.getProperty("table.selectionColor"), "#DCE8F6"),
                    gridColor = color(p.getProperty("table.gridColor"), "#9CA9B5")
                ),
                symbols = normalizeSymbols(p.getProperty("symbols", ScannerCriteria().symbols.joinToString(","))).let { stored ->
                    // Extend prior standard installation profiles with the broader liquid
                    // universe without overwriting a genuinely customized watchlist.
                    val defaults = ScannerCriteria().symbols
                    if (stored.size in setOf(50, 100) && stored.all(defaults::contains) &&
                        "AAPL" in stored && "STLAM.MI" in stored
                    ) {
                        (stored + defaults).distinct()
                    } else stored
                }
            )
        }.onFailure { log.error(LogTag.IO, "scanner settings load failed", it) }.getOrDefault(ScannerCriteria())
    }

    fun save(value: ScannerCriteria) {
        log.debug(LogTag.IO, "save(symbols={})", value.symbols.size)
        Files.createDirectories(path.parent)
        val p = Properties().apply {
            setProperty("anomalyWindow", value.anomalyWindow.name); setProperty("marketRegion", value.marketRegion.name)
            setProperty("scanIntervalSeconds", value.scanIntervalSeconds.toString()); setProperty("resultLimit", value.resultLimit.toString())
            setProperty("minPrice", value.minPrice.toString()); setProperty("minSessionTurnover", value.minSessionTurnover.toString())
            setProperty("baselineSessions", value.baselineSessions.toString())
            setProperty("maxSignalAgeMinutes", value.maxSignalAgeMinutes.toString())
            setProperty("minJumpZ", value.minJumpZ.toString())
            setProperty("minRangeZ", value.minRangeZ.toString())
            setProperty("minVolumeZ", value.minVolumeZ.toString())
            setProperty("minRelativeVolume", value.minRelativeVolume.toString())
            setProperty("minBodyRatio", value.minBodyRatio.toString())
            setProperty("minAbsoluteMovePercent", value.minAbsoluteMovePercent.toString())
            setProperty("minimumTableResults", value.minimumTableResults.toString())
            setProperty("trendWindowMinutes", value.trendWindowMinutes.toString())
            setProperty("minTrendReturnPercent", value.minTrendReturnPercent.toString())
            setProperty("minTrendEfficiency", value.minTrendEfficiency.toString())
            setProperty("displayCurrency", value.displayCurrency.name)
            setProperty("table.fontFamily", value.tableAppearance.fontFamily)
            setProperty("table.fontSize", value.tableAppearance.fontSize.toString())
            setProperty("table.textColor", value.tableAppearance.textColor)
            setProperty("table.evenRowColor", value.tableAppearance.evenRowColor)
            setProperty("table.oddRowColor", value.tableAppearance.oddRowColor)
            setProperty("table.selectionColor", value.tableAppearance.selectionColor)
            setProperty("table.gridColor", value.tableAppearance.gridColor)
            setProperty("symbols", normalizeSymbols(value.symbols.joinToString(",")).joinToString(","))
        }
        Files.newOutputStream(path).use { p.store(it, "MiMiTrends scanner settings") }
    }

    fun normalizeSymbols(text: String): List<String> {
        log.debug(LogTag.IO, "normalizeSymbols(chars={})", text.length)
        return text.split(',', ';', '\n', ' ', '\t').map(String::trim).filter(String::isNotEmpty).map(String::uppercase).distinct()
    }

    private fun color(value: String?, fallback: String): String =
        value?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: fallback

    private inline fun <reified T : Enum<T>> enumValue(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value ?: fallback.name) }.getOrDefault(fallback)
}
