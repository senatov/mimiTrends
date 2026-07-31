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
import org.senatov.mimitrends.charts.TrendChartView
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.scanner.ScannerEngine
import org.senatov.mimitrends.scanner.ScannerSettingsService
import org.senatov.mimitrends.ws.FinnhubWebSocketClient
import org.senatov.mimitrends.ws.FinnhubProfileClient
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

class MainController(
    private val apiKey: String?, initialSymbol: String = "AAPL", initialRange: String = "3M",
    initialDividerPosition: Double = 0.34
) {
    private val log = LoggerFactory.getLogger(MainController::class.java)
    private val repository = MarketRepository()
    private var currentSymbol = initialSymbol
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
    private val scannerEngine = ScannerEngine(repository)
    private val profileService = apiKey?.let { CompanyProfileService(repository, FinnhubProfileClient(it)) }
    private val scannerPanel = ScannerPanel(::openScannerSymbol, profileService?.let { service -> service::load })
    private var webSocket: FinnhubWebSocketClient? = null
    private val batchScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-scanner-rotation").apply { isDaemon = true }
    }
    private var rotationTask: ScheduledFuture<*>? = null
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

        if (apiKey == null) {
            setStatus("Add FINNHUB_API_KEY to the environment or a local .env file", true)
            refreshButton.isDisable = true
        } else {
            startScanner()
            Platform.runLater { loadLocalChart(currentSymbol) }
            setStatus("Requesting ECB EUR/USD reference rate", false)
            exchangeRates.refresh().whenComplete(BiConsumer<Double?, Throwable?> { rate, error ->
                if (error != null) log.warn(LogTag.API, "ECB exchange-rate refresh failed; cached rate remains active", error)
                Platform.runLater {
                    scannerPanel.setCurrency(scannerCriteria.displayCurrency, ::displayPrice)
                    loadLocalChart(currentSymbol)
                    if (error == null && rate != null) setStatus("Read ECB EUR/USD reference rate: $rate", false)
                }
            })
        }
        return root
    }

    fun close() {
        log.debug(LogTag.UI, "close()")
        rotationTask?.cancel(false)
        webSocket?.close()
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
        val key = apiKey ?: return
        rotationTask?.cancel(false)
        webSocket?.close(); scannerPanel.clear()
        webSocket = FinnhubWebSocketClient(key, { tick ->
            runCatching { scannerEngine.accept(tick, scannerCriteria) }
                .onSuccess { result -> result?.let {
                    Platform.runLater {
                        scannerPanel.update(it)
                        setStatus("Read ${it.symbol}: price ${"%.2f".format(it.price)}, session volume ${"%,.0f".format(it.sessionVolume)}", false)
                    }
                } }
                .onFailure { error -> log.error(LogTag.DB, "scanner evaluation failed symbol={}", tick.symbol, error) }
        }, { error ->
            log.error(LogTag.API, "scanner websocket failed", error)
            Platform.runLater { setStatus("Scanner WebSocket: ${error.message ?: "connection error"}", true, formatErrorLog("scanner", error)) }
        }).also { client ->
            val batches = scannerCriteria.symbols.chunked(scannerCriteria.batchSize.coerceIn(1, 50))
            var batchIndex = 0
            var active = emptyList<String>()
            fun activateNextBatch() {
                log.debug(LogTag.API, "activateNextBatch(index={}, total={})", batchIndex, batches.size)
                active.forEach(client::unsubscribe)
                active = batches[batchIndex]
                active.forEach(client::subscribe)
                val shownIndex = batchIndex + 1
                val shownSymbols = active.toList()
                Platform.runLater {
                    scannerPanel.showBatch(shownIndex, batches.size, shownSymbols, scannerCriteria.rotationSeconds)
                    setStatus("Requesting Finnhub batch $shownIndex/${batches.size}: ${shownSymbols.joinToString(", ")}", false)
                }
                batchIndex = (batchIndex + 1) % batches.size
            }
            activateNextBatch()
            client.connect().thenRun { Platform.runLater { setStatus("Finnhub WebSocket connected · reading realtime trades", false) } }
                .exceptionally { error -> log.error(LogTag.API, "scanner connection failed", error); null }
            if (batches.size > 1) rotationTask = batchScheduler.scheduleAtFixedRate(
                { runCatching(::activateNextBatch).onFailure { log.error(LogTag.API, "scanner batch rotation failed", it) } },
                scannerCriteria.rotationSeconds, scannerCriteria.rotationSeconds, TimeUnit.SECONDS
            )
        }
    }

    private fun openScannerSymbol(symbol: String) {
        log.debug(LogTag.UI, "openScannerSymbol(symbol={})", symbol)
        currentSymbol = symbol
        loadLocalChart(symbol)
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
        Alert(Alert.AlertType.INFORMATION).apply {
            aboutButton.scene?.window?.let(::initOwner)
            title = "About MiMiTrends"
            headerText = "MiMiTrends 1.0"
            contentText = """Kotlin · JavaFX market momentum scanner

Market data: Finnhub
Currency reference rates: European Central Bank
Local storage: ~/.mimi/trends/

Read-only demonstration application.
© 2026 MiMiTrends"""
            buttonTypes.setAll(ButtonType.OK)
            isResizable = false
        }.showAndWait()
    }

    private fun loadLocalChart(symbol: String) {
        log.debug(LogTag.UI, "loadLocalChart(symbol={})", symbol)
        if (symbol.isBlank()) return
        setLoading(true)
        val days = selectedDays()
        setStatus("Requesting SQLite: $symbol · $selectedRangeValue", false)
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
                        trendChart.renderMinuteBars(symbol, bars, selectedRangeValue, displayPrice(symbol, 1.0), currency.symbol)
                        setStatus("Read SQLite: $symbol · ${bars.size} minute bars · $selectedRangeValue", false)
                    } else {
                        trendChart.clear()
                        setStatus("Read SQLite: no collected minute bars for $symbol · $selectedRangeValue", false)
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

    private fun setStatus(message: String, error: Boolean) {
        log.debug(LogTag.UI, "setStatus(message={}, error={})", message, error)
        setStatus(message, error, if (error) formatErrorLog(currentSymbol, null, message) else null)
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
