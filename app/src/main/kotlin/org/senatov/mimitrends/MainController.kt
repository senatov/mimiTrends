package org.senatov.mimitrends

import javafx.application.Platform
import javafx.animation.Interpolator
import javafx.animation.RotateTransition
import javafx.animation.ScaleTransition
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import org.senatov.mimitrends.charts.TrendChartView
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.MarketRegion
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.scanner.ScannerEngine
import org.senatov.mimitrends.scanner.ScannerSettingsService
import org.senatov.mimitrends.ws.FinnhubProfileClient
import org.senatov.mimitrends.marketdata.YahooFinanceClient
import org.senatov.mimitrends.marketdata.CompanyLogoClient
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import javafx.util.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.concurrent.atomic.AtomicLong

class MainController(
    private val apiKey: String?, initialSymbol: String = "AAPL", initialRange: String = "3M",
    initialDividerPosition: Double = 0.34
) {
    private val log = LoggerFactory.getLogger(MainController::class.java)
    private val repository = MarketRepository()
    private var currentSymbol = initialSymbol
    private var currentSignal: ScanResult? = null
    private var selectedRangeValue = initialRange.takeIf { it in setOf("1D", "5D", "1M", "3M", "6M", "1Y") } ?: "3M"
    private val refreshButton = Button("↻")
    private val settingsButton = Button("⚙")
    private val aboutButton = Button("ⓘ")
    private val statusLabel = Label()
    private val errorDetailsButton = Button("!")
    private var lastErrorDetails: String? = null
    private val trendChart = TrendChartView()
    private val scannerSettings = ScannerSettingsService()
    private var scannerCriteria: ScannerCriteria = scannerSettings.load()
    private val scannerEngine = ScannerEngine()
    private val yahooFinance = YahooFinanceClient()
    private val profileService = CompanyProfileService(
        repository, apiKey?.let(::FinnhubProfileClient), CompanyLogoClient()
    )
    private val scannerPanel = ScannerPanel(::openScannerResult, profileService::load)
    private val batchScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-scanner-rotation").apply { isDaemon = true }
    }
    private var rotationTask: ScheduledFuture<*>? = null
    private val scanGeneration = AtomicLong()
    private val exchangeRates = ExchangeRateService()
    private val initialDivider = initialDividerPosition.coerceIn(0.15, 0.75)
    private val contentSplitPane = SplitPane()

    fun createView(): Parent {
        log.debug(LogTag.UI, "createView()")
        scannerPanel.setCurrency(scannerCriteria.displayCurrency, ::displayPrice)
        scannerPanel.setAppearance(scannerCriteria.tableAppearance)
        configureIconButton(refreshButton, "Refresh local chart", rotateOnHover = true)
        refreshButton.setOnAction { loadLocalChart(currentSymbol) }
        configureIconButton(settingsButton, "Scanner and currency settings", rotateOnHover = false)
        settingsButton.setOnAction { showScannerSettings() }
        configureIconButton(aboutButton, "About MiMiTrends", rotateOnHover = false)
        aboutButton.setOnAction { showAbout() }

        val titleBar = HBox(
            8.0,
            Label("MiMiTrends").apply { styleClass += "app-title" },
            spacer(),
            refreshButton,
            settingsButton,
            aboutButton
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "title-toolbar"
        }

        contentSplitPane.apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            items.setAll(scannerPanel, trendChart)
            SplitPane.setResizableWithParent(scannerPanel, true)
            SplitPane.setResizableWithParent(trendChart, true)
            styleClass += "content-split-pane"
        }
        Platform.runLater { contentSplitPane.setDividerPosition(0, initialDivider) }

        val content = VBox(contentSplitPane).apply {
            padding = Insets(22.0, 24.0, 16.0, 24.0)
            VBox.setVgrow(contentSplitPane, Priority.ALWAYS)
        }

        errorDetailsButton.apply {
            styleClass += "error-details-button"
            tooltip = Tooltip("Show complete error log")
            isVisible = false
            isManaged = false
            setOnAction { showErrorDetails() }
        }
        val requestStatusBar = HBox(8.0, statusLabel, spacer(), errorDetailsButton).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "request-status-bar"
        }
        val toolbar = VBox(titleBar, requestStatusBar)
        val root = BorderPane(content, toolbar, null, null, null)
        root.styleClass += "app-root"

        startScanner()
        Platform.runLater { loadLocalChart(currentSymbol) }
        setStatus("Requesting ECB EUR/USD reference rate")
        exchangeRates.refresh().whenComplete(BiConsumer<Double?, Throwable?> { rate, error ->
            if (error != null) log.warn(LogTag.API, "ECB exchange-rate refresh failed; cached rate remains active", error)
            Platform.runLater {
                scannerPanel.setCurrency(scannerCriteria.displayCurrency, ::displayPrice)
                loadLocalChart(currentSymbol)
                if (error == null && rate != null) setStatus("Read ECB EUR/USD reference rate: $rate")
            }
        })
        return root
    }

    fun close() {
        log.debug(LogTag.UI, "close()")
        rotationTask?.cancel(false)
        batchScheduler.shutdownNow()
        repository.close()
    }

    fun selectedSymbol(): String {
        log.debug(LogTag.UI, "selectedSymbol()")
        return currentSymbol.ifEmpty { "AAPL" }
    }

    fun selectedRange(): String {
        log.debug(LogTag.UI, "selectedRange()")
        return selectedRangeValue
    }

    fun dividerPosition(): Double {
        log.debug(LogTag.UI, "dividerPosition()")
        return contentSplitPane.dividers.firstOrNull()?.position ?: initialDivider
    }

    private fun startScanner() {
        log.debug(LogTag.API, "startScanner(symbols={})", scannerCriteria.symbols.size)
        val generation = scanGeneration.incrementAndGet()
        val criteria = scannerCriteria
        rotationTask?.cancel(false)
        scannerPanel.clear()
        val symbols = selectedMarketSymbols()
        lateinit var scan: () -> Unit
        scan = scan@ {
            log.debug(LogTag.API, "scanYahoo(symbols={}, recentWindow=15m)", symbols.size)
            Platform.runLater {
                scannerPanel.beginScan(1, 1, symbols)
                setStatus("Yahoo/SQLite: scanning ${symbols.size} symbols · recent 0–15m signals")
            }
            val results = mutableListOf<ScanResult>()
            val errors = mutableListOf<String>()
            symbols.forEachIndexed { index, symbol ->
                if (generation != scanGeneration.get()) return@forEachIndexed
                runCatching { loadAndEvaluate(symbol, criteria) }
                    .onSuccess { result -> result?.let(results::add) }
                    .onFailure { error ->
                        errors += "$symbol: ${error.message ?: error.javaClass.simpleName}"
                        log.warn(LogTag.API, "Yahoo scan failed symbol={}", symbol, error)
                    }
                Platform.runLater { setStatus("Yahoo Finance: analyzed ${index + 1}/${symbols.size} · $symbol") }
            }
            repository.flushPending()
            Platform.runLater {
                if (generation != scanGeneration.get()) return@runLater
                if (results.isEmpty()) {
                    scannerPanel.abortScan()
                    setStatus("Yahoo scan produced no data; previous table retained", true, errors.joinToString("\n"))
                } else {
                    results.forEach(scannerPanel::update)
                    scannerPanel.completeScan(criteria.resultLimit)
                    scannerPanel.showCountdown(criteria.scanIntervalSeconds)
                    setStatus("Yahoo/SQLite scan complete · ${results.size} ranked · next in ${criteria.scanIntervalSeconds}s")
                }
            }
            if (generation != scanGeneration.get()) return@scan
            rotationTask = batchScheduler.schedule(
                { runCatching(scan).onFailure { log.error(LogTag.API, "scheduled Yahoo scan failed", it) } },
                criteria.scanIntervalSeconds, TimeUnit.SECONDS
            )
        }
        batchScheduler.execute { runCatching(scan).onFailure { log.error(LogTag.API, "initial Yahoo scan failed", it) } }
    }

    private fun loadAndEvaluate(symbol: String, criteria: ScannerCriteria): ScanResult? {
        log.debug(LogTag.API, "loadAndEvaluate(symbol={})", symbol)
        val now = java.time.Instant.now().epochSecond
        val freshAfter = now - criteria.scanIntervalSeconds
        val latestLocal = repository.latestMinuteEpoch(symbol)
        val localFresh = latestLocal != null && (!isMarketOpen(symbol) || latestLocal >= freshAfter)
        val bars = if (localFresh) {
            log.debug(LogTag.DB, "using fresh SQLite bars symbol={}", symbol)
            repository.loadMinuteBars(symbol, now - 7 * 86_400)
        } else {
            // Yahoo permits one-minute history only for a short recent window. An old cache is
            // refreshed with the normal five-day bootstrap instead of an invalid long request.
            val incrementalAfter = latestLocal?.takeIf { it >= now - 7 * 86_400 }
            val series = yahooFinance.loadIntraday(symbol, incrementalAfter)
            series.bars.forEach(repository::upsertMinuteBar)
            val oldProfile = repository.loadCompanyProfile(symbol)
            repository.upsertCompanyProfile(CompanyProfile(
                symbol, series.companyName, series.exchange, oldProfile?.logoUrl,
                oldProfile?.logoBytes, System.currentTimeMillis()
            ))
            repository.loadMinuteBars(symbol, now - 30 * 86_400)
        }
        return scannerEngine.evaluate(symbol, bars, criteria)
    }

    private fun isMarketOpen(symbol: String, instant: java.time.Instant = java.time.Instant.now()): Boolean {
        log.trace(LogTag.STATE, "isMarketOpen(symbol={})", symbol)
        val european = symbol.contains('.')
        val zone = java.time.ZoneId.of(if (european) "Europe/Berlin" else "America/New_York")
        val local = instant.atZone(zone)
        if (local.dayOfWeek.value > 5) return false
        val time = local.toLocalTime()
        return if (european) {
            time >= java.time.LocalTime.of(8, 50) && time <= java.time.LocalTime.of(17, 40)
        } else {
            time >= java.time.LocalTime.of(9, 25) && time <= java.time.LocalTime.of(16, 10)
        }
    }

    private fun selectedMarketSymbols(): List<String> {
        log.debug(LogTag.STATE, "selectedMarketSymbols(region={})", scannerCriteria.marketRegion)
        return scannerCriteria.symbols.filter { symbol ->
            val european = symbol.contains('.')
            when (scannerCriteria.marketRegion) {
                MarketRegion.BOTH -> true
                MarketRegion.US -> !european
                MarketRegion.EUROPE -> european
            }
        }
    }

    private fun openScannerResult(result: ScanResult) {
        log.debug(LogTag.UI, "openScannerResult(symbol={}, age={})", result.symbol, result.signalAgeMinutes)
        currentSymbol = result.symbol
        currentSignal = result
        loadLocalChart(result.symbol)
    }

    private fun showScannerSettings() {
        log.debug(LogTag.UI, "showScannerSettings()")
        ScannerSettingsDialog(refreshButton.scene?.window, scannerCriteria, scannerSettings).showAndWait()?.let {
            scannerCriteria = it; scannerSettings.save(it)
            scannerPanel.setCurrency(it.displayCurrency, ::displayPrice)
            scannerPanel.setAppearance(it.tableAppearance)
            loadLocalChart(currentSymbol)
            startScanner()
        }
    }

    private fun showAbout() {
        log.debug(LogTag.UI, "showAbout()")
        Dialog<ButtonType>().apply {
            aboutButton.scene?.window?.let(::initOwner)
            title = "About MiMiTrends"
            dialogPane.buttonTypes.setAll(ButtonType.OK)
            dialogPane.headerText = "MiMiTrends ${BuildInfo.displayVersion}"
            javaClass.getResourceAsStream("/icons/icon_128x128.png")?.use { stream ->
                dialogPane.graphic = ImageView(Image(stream)).apply {
                    fitWidth = 72.0; fitHeight = 72.0; isPreserveRatio = true
                }
            }
            dialogPane.content = TabPane(
                aboutTab("Overview", """
                    Local-first market anomaly scanner for macOS, Linux, and Windows.

                    Market data       Yahoo Finance (default); Finnhub profile fallback (optional)
                    Currency rates    European Central Bank
                    Local database    ~/.mimi/trends/mimitrends.db
                    Settings          ~/.mimi/trends/
                    Log file          /tmp/MiMiTrends.log

                    Read-only demonstration application. It does not place orders.
                    © 2026 MiMiTrends
                """.trimIndent()),
                aboutTab("Libraries", """
                    Kotlin Standard Library ${KotlinVersion.CURRENT} — Apache License 2.0
                    JavaFX ${System.getProperty("javafx.runtime.version", "26")} — GPLv2 with Classpath Exception
                    AtlantaFX 2.1.0 — MIT License
                    JFreeChart 1.5.6 — LGPL 2.1 or later
                    JFreeChart-FX 2.0.2 — LGPL 2.1 or later
                    SQLite JDBC 3.50.3.0 — Apache License 2.0
                    Jackson Databind 2.22.1 — Apache License 2.0
                    SLF4J API 2.0.17 — MIT License
                    Apache Log4j 2.26.1 — Apache License 2.0

                    Data and branding services are not bundled libraries. Their availability and
                    terms are governed by the respective providers.
                """.trimIndent()),
                aboutTab("System", """
                    Application       ${BuildInfo.displayVersion}
                    Java runtime      ${System.getProperty("java.runtime.version")}
                    Java VM           ${System.getProperty("java.vm.name")}
                    JavaFX runtime    ${System.getProperty("javafx.runtime.version", "26")}
                    Operating system  ${System.getProperty("os.name")} ${System.getProperty("os.version")}
                    Architecture      ${System.getProperty("os.arch")}
                    Locale            ${java.util.Locale.getDefault().toLanguageTag()}
                """.trimIndent())
            ).apply { tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE }
            dialogPane.prefWidth = 680.0
            dialogPane.prefHeight = 520.0
            isResizable = true
            setResultConverter { it }
        }.showAndWait()
    }

    private fun aboutTab(title: String, text: String): Tab {
        log.debug(LogTag.UI, "aboutTab(title={})", title)
        return Tab(title, TextArea(text).apply {
            isEditable = false
            isWrapText = true
            style = "-fx-font-family: 'SF Pro Display'; -fx-font-size: 13px;"
        })
    }

    private fun loadLocalChart(symbol: String) {
        log.debug(LogTag.UI, "loadLocalChart(symbol={})", symbol)
        if (symbol.isBlank()) return
        setLoading(true)
        val days = selectedDays()
        setStatus("Requesting SQLite: $symbol · $selectedRangeValue")
        CompletableFuture.supplyAsync {
            repository.loadMinuteBars(symbol, java.time.Instant.now().minusSeconds(days * 86_400).epochSecond)
        }.whenComplete(BiConsumer<List<MinuteBar>?, Throwable?> { bars, error ->
                Platform.runLater {
                    setLoading(false)
                    if (error != null) {
                        log.error(LogTag.DB, "local chart load failed symbol={}", symbol, error)
                        setStatus("SQLite read failed: ${error.message ?: "unknown error"}", true, formatErrorLog(symbol, error))
                    } else if (!bars.isNullOrEmpty()) {
                        val currency = scannerCriteria.displayCurrency
                        trendChart.renderMinuteBars(
                            symbol, bars, selectedRangeValue, displayPrice(symbol, 1.0), currency.symbol,
                            currentSignal?.takeIf { it.symbol == symbol }?.signalAgeMinutes
                        )
                        setStatus("Read SQLite: $symbol · ${bars.size} minute bars · $selectedRangeValue")
                    } else {
                        trendChart.clear()
                        setStatus("Read SQLite: no collected minute bars for $symbol · $selectedRangeValue")
                    }
                }
            })
    }

    private fun selectedDays(): Long {
        log.debug(LogTag.UI, "selectedDays(range={})", selectedRangeValue)
        return when (selectedRangeValue) {
        "1D" -> 1
        "5D" -> 5
        "1M" -> 30
        "6M" -> 180
        "1Y" -> 365
        else -> 90
        }
    }

    private fun setLoading(value: Boolean) {
        log.debug(LogTag.UI, "setLoading(value={})", value)
        trendChart.setLoading(value)
        refreshButton.isDisable = value
    }

    private fun setStatus(message: String) {
        log.debug(LogTag.UI, "setStatus(message={})", message)
        setStatus(message, false, null)
    }

    private fun setStatus(message: String, error: Boolean, details: String?) {
        log.debug(LogTag.UI, "setStatus(message={}, error={}, details={})", message, error, details != null)
        statusLabel.text = message
        statusLabel.styleClass.removeAll("status-error")
        if (error) statusLabel.styleClass += "status-error"
        lastErrorDetails = details
        errorDetailsButton.isVisible = error && !details.isNullOrBlank()
        errorDetailsButton.isManaged = errorDetailsButton.isVisible
    }

    private fun showErrorDetails() {
        log.debug(LogTag.UI, "showErrorDetails()")
        val details = lastErrorDetails ?: return
        val textArea = TextArea(details).apply {
            isEditable = false
            isWrapText = false
            prefColumnCount = 100
            prefRowCount = 28
            styleClass += "error-log-area"
        }
        Dialog<ButtonType>().apply {
            errorDetailsButton.scene?.window?.let(::initOwner)
            title = "MiMiTrends error log"
            headerText = statusLabel.text
            dialogPane.content = textArea
            dialogPane.buttonTypes += ButtonType.CLOSE
            isResizable = true
        }.showAndWait()
    }

    private fun formatErrorLog(query: String, error: Throwable?, message: String? = null): String {
        log.debug(LogTag.UI, "formatErrorLog(query={}, hasError={})", query, error != null)
        val stackTrace = if (error == null) {
            message ?: "No exception stack trace is available."
        } else {
            StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
        }
        return buildString {
            appendLine("MiMiTrends error report")
            appendLine("Time: ${ZonedDateTime.now()}")
            appendLine("Query: $query")
            appendLine("Range: $selectedRangeValue")
            appendLine()
            append(stackTrace)
        }
    }

    private fun displayPrice(symbol: String, value: Double): Double {
        log.trace(LogTag.UI, "displayPrice(symbol={}, value={})", symbol, value)
        val sourceIsEuro = symbol.uppercase().let { it.endsWith(".DE") || it.endsWith(".F") || it.endsWith(".PA") || it.endsWith(".AS") }
        return when (scannerCriteria.displayCurrency) {
            org.senatov.mimitrends.model.DisplayCurrency.EUR -> if (sourceIsEuro) value else exchangeRates.usdToEur(value)
            org.senatov.mimitrends.model.DisplayCurrency.USD -> if (sourceIsEuro) exchangeRates.eurToUsd(value) else value
        }
    }

    private fun spacer(): Region {
        log.debug(LogTag.UI, "spacer()")
        return Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
    }

    private fun configureIconButton(button: Button, tooltipText: String, rotateOnHover: Boolean) {
        log.debug(LogTag.UI, "configureIconButton(tooltip={}, rotate={})", tooltipText, rotateOnHover)
        button.styleClass += "toolbar-icon-button"
        button.tooltip = Tooltip(tooltipText).apply {
            showDelay = Duration.millis(350.0); hideDelay = Duration.millis(120.0)
            styleClass += "mimi-tooltip"
        }
        val scale = ScaleTransition(Duration.millis(150.0), button).apply { interpolator = Interpolator.EASE_BOTH }
        button.setOnMouseEntered {
            scale.stop(); scale.toX = 1.08; scale.toY = 1.08; scale.playFromStart()
            if (rotateOnHover) RotateTransition(Duration.millis(240.0), button).apply { byAngle = 24.0; interpolator = Interpolator.EASE_BOTH }.play()
        }
        button.setOnMouseExited {
            scale.stop(); scale.toX = 1.0; scale.toY = 1.0; scale.playFromStart()
            if (rotateOnHover) RotateTransition(Duration.millis(180.0), button).apply { toAngle = 0.0; interpolator = Interpolator.EASE_BOTH }.play()
        }
        button.setOnMousePressed { button.scaleX = 0.94; button.scaleY = 0.94 }
        button.setOnMouseReleased { button.scaleX = 1.08; button.scaleY = 1.08 }
    }
}
