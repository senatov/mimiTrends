package org.senatov.mimitrends

import javafx.application.Platform
import javafx.animation.Interpolator
import javafx.animation.RotateTransition
import javafx.animation.ScaleTransition
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.layout.*
import org.senatov.mimitrends.charts.TrendChartView
import org.senatov.mimitrends.api.FinnhubClient
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.model.MarketSnapshot
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.scanner.ScannerEngine
import org.senatov.mimitrends.scanner.ScannerSettingsService
import org.senatov.mimitrends.ws.FinnhubWebSocketClient
import org.slf4j.LoggerFactory
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime
import javafx.util.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer

class MainController(private val apiKey: String?, initialSymbol: String = "AAPL", initialRange: String = "3M") {
    private val log = LoggerFactory.getLogger(MainController::class.java)
    private val repository = MarketRepository()
    private val symbolField = TextField(initialSymbol)
    private val rangeBox = ComboBox(FXCollections.observableArrayList("1D", "5D", "1M", "3M", "6M", "1Y"))
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
    private val scannerPanel = ScannerPanel(::openScannerSymbol)
    private var webSocket: FinnhubWebSocketClient? = null
    private val batchScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-scanner-rotation").apply { isDaemon = true }
    }
    private var rotationTask: ScheduledFuture<*>? = null
    private val exchangeRates = ExchangeRateService()
    private var currentSnapshot: MarketSnapshot? = null
    private val initialRangeValue = initialRange.takeIf { it in setOf("1D", "5D", "1M", "3M", "6M", "1Y") } ?: "3M"

    fun createView(): Parent {
        log.debug(LogTag.UI, "createView()")
        scannerPanel.setCurrency(scannerCriteria.displayCurrency, ::displayPrice)
        rangeBox.value = initialRangeValue
        rangeBox.setOnAction { if (rangeBox.scene != null && !refreshButton.isDisable) refresh() }
        symbolField.promptText = "Ticker, e.g. AAPL"
        symbolField.prefColumnCount = 12
        symbolField.setOnAction { refresh() }
        configureIconButton(refreshButton, "Refresh market data", rotateOnHover = true)
        refreshButton.setOnAction { refresh() }
        configureIconButton(settingsButton, "Scanner and currency settings", rotateOnHover = false)
        settingsButton.setOnAction { showScannerSettings() }
        configureIconButton(aboutButton, "About MiMiTrends", rotateOnHover = false)
        aboutButton.setOnAction { showAbout() }

        val titleBar = HBox(
            8.0,
            Label("MiMiTrends").apply { styleClass += "app-title" },
            spacer(),
            aboutButton,
            settingsButton,
            refreshButton
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "title-toolbar"
        }

        val searchBar = HBox(
            8.0,
            Label("Symbol"),
            symbolField,
            Separator(),
            Label("Range"),
            rangeBox
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "search-toolbar"
        }
        val toolbar = VBox(titleBar, searchBar)

        VBox.setVgrow(trendChart, Priority.ALWAYS)

        val content = VBox(14.0, scannerPanel, Separator(), trendChart).apply {
            padding = Insets(22.0, 24.0, 16.0, 24.0)
            VBox.setVgrow(trendChart, Priority.ALWAYS)
        }

        errorDetailsButton.apply {
            styleClass += "error-details-button"
            tooltip = Tooltip("Show complete error log")
            isVisible = false
            isManaged = false
            setOnAction { showErrorDetails() }
        }
        val statusBar = HBox(8.0, statusLabel, spacer(), errorDetailsButton).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "status-bar"
        }
        val root = BorderPane(content, toolbar, null, statusBar, null)
        root.styleClass += "app-root"

        if (apiKey == null) {
            setStatus("Add FINNHUB_API_KEY to the environment or a local .env file", true)
            refreshButton.isDisable = true
        } else {
            startScanner()
            Platform.runLater(::refresh)
            exchangeRates.refresh().whenComplete(BiConsumer<Double?, Throwable?> { _, error ->
                if (error != null) log.warn(LogTag.API, "ECB exchange-rate refresh failed; cached rate remains active", error)
                Platform.runLater {
                    scannerPanel.setCurrency(scannerCriteria.displayCurrency, ::displayPrice)
                    currentSnapshot?.let(::showSnapshot)
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
    }

    fun selectedSymbol(): String {
        log.debug(LogTag.UI, "selectedSymbol()")
        return symbolField.text.trim().ifEmpty { "AAPL" }
    }

    fun selectedRange(): String {
        log.debug(LogTag.UI, "selectedRange()")
        return rangeBox.value ?: "3M"
    }

    private fun startScanner() {
        log.debug(LogTag.API, "startScanner(symbols={})", scannerCriteria.symbols.size)
        val key = apiKey ?: return
        rotationTask?.cancel(false)
        webSocket?.close(); scannerPanel.clear()
        webSocket = FinnhubWebSocketClient(key, { tick ->
            runCatching { scannerEngine.accept(tick, scannerCriteria) }
                .onSuccess { result -> result?.let { Platform.runLater { scannerPanel.update(it) } } }
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
                Platform.runLater { scannerPanel.showBatch(shownIndex, batches.size, shownSymbols, scannerCriteria.rotationSeconds) }
                batchIndex = (batchIndex + 1) % batches.size
            }
            activateNextBatch()
            client.connect().exceptionally { error -> log.error(LogTag.API, "scanner connection failed", error); null }
            if (batches.size > 1) rotationTask = batchScheduler.scheduleAtFixedRate(
                { runCatching(::activateNextBatch).onFailure { log.error(LogTag.API, "scanner batch rotation failed", it) } },
                scannerCriteria.rotationSeconds, scannerCriteria.rotationSeconds, TimeUnit.SECONDS
            )
        }
    }

    private fun openScannerSymbol(symbol: String) {
        log.debug(LogTag.UI, "openScannerSymbol(symbol={})", symbol)
        symbolField.text = symbol
        refresh()
    }

    private fun showScannerSettings() {
        log.debug(LogTag.UI, "showScannerSettings()")
        ScannerSettingsDialog(refreshButton.scene?.window, scannerCriteria, scannerSettings).showAndWait()?.let {
            scannerCriteria = it; scannerSettings.save(it)
            scannerPanel.setCurrency(it.displayCurrency, ::displayPrice)
            currentSnapshot?.let(::showSnapshot)
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

    private fun refresh() {
        log.debug(LogTag.UI, "refresh()")
        val key = apiKey ?: return
        val query = symbolField.text.trim()
        if (query.isEmpty()) {
            setStatus("Enter a ticker, company name, ISIN or WKN", true)
            return
        }
        setLoading(true)
        log.info(LogTag.API, "loading query={} rangeDays={}", query, selectedDays())
        setStatus("Searching for $query…", false)
        val days = selectedDays()
        FinnhubClient(
            apiKey = key,
            premiumCandlesEnabled = ApiKeyResolver.premiumCandlesEnabled()
        ).resolveAndLoadSnapshot(query, days)
            .thenApply { snapshot ->
                runCatching {
                    repository.save(snapshot)
                    repository.load(snapshot.symbol, days)?.copy(
                        description = snapshot.description,
                        fromCache = false
                    ) ?: snapshot
                }.onFailure { error ->
                    log.error(LogTag.DB, "cache update failed symbol={}", snapshot.symbol, error)
                }.getOrDefault(snapshot)
            }
            .whenComplete { snapshot: MarketSnapshot?, error: Throwable? ->
                val cached = if (error != null) {
                    runCatching { repository.load(query.uppercase(), days) }
                        .onFailure { cacheError -> log.error(LogTag.DB, "cache fallback failed query={}", query, cacheError) }
                        .getOrNull()
                } else null
                Platform.runLater {
                    setLoading(false)
                    if (error != null) {
                        val cause = (error as? CompletionException)?.cause ?: error
                        log.error(LogTag.API, "load failed query={}", query, cause)
                        if (cached != null) showSnapshot(cached)
                        setStatus(
                            if (cached != null) "Finnhub temporarily unavailable — showing cached data"
                            else cause.message ?: "Could not load market data",
                            true,
                            formatErrorLog(query, cause)
                        )
                    } else if (snapshot != null) {
                        log.info(LogTag.API, "load completed symbol={} points={}", snapshot.symbol, snapshot.candles.size)
                        showSnapshot(snapshot)
                    } else {
                        setStatus("Finnhub returned an empty response", true)
                    }
                }
            }
    }

    private fun showSnapshot(snapshot: MarketSnapshot) {
        log.debug(LogTag.UI, "showSnapshot(symbol={}, points={})", snapshot.symbol, snapshot.candles.size)
        currentSnapshot = snapshot
        val currency = scannerCriteria.displayCurrency
        symbolField.text = snapshot.symbol

        val minuteBars = runCatching { repository.loadMinuteBars(snapshot.symbol, java.time.Instant.now().minusSeconds(selectedDays() * 86_400).epochSecond) }
            .onFailure { log.error(LogTag.DB, "minute bars load failed symbol={}", snapshot.symbol, it) }.getOrDefault(emptyList())
        val multiplier = displayPrice(snapshot.symbol, 1.0)
        if (minuteBars.isNotEmpty()) trendChart.renderMinuteBars(snapshot.symbol, minuteBars, rangeBox.value, multiplier, currency.symbol)
        else trendChart.render(snapshot, rangeBox.value, multiplier, currency.symbol)
        setStatus(
            when {
                snapshot.fromCache -> "Showing ${snapshot.candles.size} cached real price points"
                snapshot.candles.isEmpty() -> "Live quote saved · local history collection has started"
                else -> "${snapshot.candles.size} real price points loaded"
            },
            false
        )
    }

    private fun selectedDays(): Long {
        log.debug(LogTag.UI, "selectedDays(range={})", rangeBox.value)
        return when (rangeBox.value) {
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
        symbolField.isDisable = value
        rangeBox.isDisable = value
    }

    private fun setStatus(message: String, error: Boolean) {
        log.debug(LogTag.UI, "setStatus(message={}, error={})", message, error)
        setStatus(message, error, if (error) formatErrorLog(symbolField.text, null, message) else null)
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
            appendLine("Range: ${rangeBox.value}")
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
