package org.senatov.mimitrends

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.geometry.Side
import javafx.stage.Window
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.TableAppearance
import org.senatov.mimitrends.model.AnomalyWindow
import org.senatov.mimitrends.model.MarketRegion
import org.senatov.mimitrends.model.UiDensity
import org.senatov.mimitrends.model.UiTheme
import javafx.collections.FXCollections
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.application.Platform
import org.senatov.mimitrends.scanner.ScannerSettingsService
import java.util.concurrent.CompletableFuture

data class ScannerSettingsResult(val criteria: ScannerCriteria, val finnhubApiKey: String?)

class ScannerSettingsDialog(
    owner: Window?, current: ScannerCriteria, private val service: ScannerSettingsService,
    finnhubConfigured: Boolean, private val validateStockSearchUrl: (String) -> Unit = {}
) {
    private val dialog = Dialog<ScannerSettingsResult>()
    private val restoreDefaults = ButtonType("Restore Defaults", ButtonBar.ButtonData.LEFT)
    private val saveSettings = ButtonType("Save", ButtonBar.ButtonData.OK_DONE)
    private val geometry = WindowGeometryService("settings", DEFAULT_WIDTH, DEFAULT_HEIGHT)
    private val marketRegion = ComboBox(FXCollections.observableArrayList(MarketRegion.entries)).apply { value = current.marketRegion }
    private val scanInterval = Spinner<Int>(60, 3_600, current.scanIntervalSeconds.toInt(), 30).apply { isEditable = true }
    private val resultLimit = Spinner<Int>(5, 15, current.resultLimit.coerceIn(5, 15), 1).apply { isEditable = true }
    private val price = Spinner<Double>(0.0, 10_000.0, current.minPrice, 0.5).apply { isEditable = true }
    private val turnover = Spinner<Double>(0.0, 10_000_000_000.0, current.minSessionTurnover, 100_000.0).apply { isEditable = true }
    private val sessions = Spinner<Int>(3, 20, current.baselineSessions, 1).apply { isEditable = true }
    private val signalAge = Spinner<Int>(0, 5, current.maxSignalAgeMinutes, 1).apply { isEditable = true }
    private val jumpZ = Spinner<Double>(1.0, 20.0, current.minJumpZ, 0.25).apply { isEditable = true }
    private val rangeZ = Spinner<Double>(1.0, 20.0, current.minRangeZ, 0.25).apply { isEditable = true }
    private val volumeZ = Spinner<Double>(0.0, 20.0, current.minVolumeZ, 0.25).apply { isEditable = true }
    private val relativeVolume = Spinner<Double>(0.0, 20.0, current.minRelativeVolume, 0.1).apply { isEditable = true }
    private val bodyRatio = Spinner<Double>(0.0, 1.0, current.minBodyRatio, 0.05).apply { isEditable = true }
    private val absoluteMove = Spinner<Double>(0.0, 10.0, current.minAbsoluteMovePercent, 0.05).apply { isEditable = true }
    private val minimumResults = Spinner<Int>(5, 15, current.minimumTableResults.coerceIn(5, 15), 1).apply { isEditable = true }
    private val trendWindow = Spinner<Int>(60, 360, current.trendWindowMinutes, 30).apply { isEditable = true }
    private val trendReturn = Spinner<Double>(0.1, 20.0, current.minTrendReturnPercent, 0.1).apply { isEditable = true }
    private val trendEfficiency = Spinner<Double>(0.01, 1.0, current.minTrendEfficiency, 0.01).apply { isEditable = true }
    private val currency = ComboBox(FXCollections.observableArrayList(DisplayCurrency.entries)).apply { value = current.displayCurrency }
    private val langSchwarzEnabled = CheckBox("Enabled").apply { isSelected = current.langSchwarzEnabled }
    private val tradegateEnabled = CheckBox("Enabled").apply { isSelected = current.tradegateEnabled }
    private val tradegateInterval = Spinner<Int>(500, 10_000, current.tradegateRequestIntervalMillis.toInt(), 250).apply {
        isEditable = true
    }
    private val euronextEnabled = CheckBox("Enabled").apply { isSelected = current.euronextEnabled }
    private val euronextInterval = Spinner<Int>(750, 15_000, current.euronextRequestIntervalMillis.toInt(), 250).apply {
        isEditable = true
    }
    private val stockSearchUrl = TextField(current.stockSearchUrl)
    private val finnhubApiKey = PasswordField().apply {
        promptText = if (finnhubConfigured) "Configured — leave blank to keep" else "Optional API key"
    }
    private val symbols = TextArea(current.symbols.joinToString(", ")).apply { prefRowCount = 3; isWrapText = true }
    private val theme = ComboBox(FXCollections.observableArrayList(UiTheme.entries)).apply {
        value = current.tableAppearance.theme
    }
    private val density = ComboBox(FXCollections.observableArrayList(UiDensity.entries)).apply {
        value = current.tableAppearance.density
    }
    private val fontFamily = ComboBox(FXCollections.observableArrayList(Font.getFamilies())).apply {
        value = current.tableAppearance.fontFamily
        prefWidth = 240.0
    }
    private val fontSize = Spinner<Double>(9.0, 22.0, current.tableAppearance.fontSize, 0.5).apply { isEditable = true }
    private val textColor = ColorPicker(Color.web(current.tableAppearance.textColor))
    private val evenRowColor = ColorPicker(Color.web(current.tableAppearance.evenRowColor))
    private val oddRowColor = ColorPicker(Color.web(current.tableAppearance.oddRowColor))
    private val selectionColor = ColorPicker(Color.web(current.tableAppearance.selectionColor))
    private val gridColor = ColorPicker(Color.web(current.tableAppearance.gridColor))

    init {
        owner?.let(dialog::initOwner)
        dialog.title = "Scanner Settings"
        dialog.headerText = "Anomaly Scanner"
        dialog.isResizable = true
        WorkspaceDialogAppearance.apply(dialog, owner)
        dialog.dialogPane.styleClass += "glass-settings-dialog"

        val scanner = VBox(14.0,
            section("Result selection",
                settingRow("Target table results", "The scanner relaxes thresholds gradually when necessary to fill approximately this many rows.", minimumResults),
                settingRow("Maximum results", "Hard display limit. Candidates below the limit are ranked by quality; extra weak rows are not appended.", resultLimit),
                settingRow("Maximum signal age", "Only the latest candle and this many preceding minutes are eligible.", signalAge),
                settingRow("Market universe", "Select US listings, European listings, or both.", marketRegion)
            ),
            section("Price signal",
                settingRow("Minimum absolute move", "Minimum percentage movement required before statistical anomalies are considered.", absoluteMove),
                settingRow("Minimum jump Z", "Return deviation measured in robust local standard deviations.", jumpZ),
                settingRow("Minimum range Z", "High–low range deviation that can independently identify an impulse.", rangeZ),
                settingRow("Minimum candle body", "Body divided by candle range. Higher values reject wick-heavy and indecisive candles.", bodyRatio)
            ),
            section("Persistent growth",
                settingRow("Trend window", "Minutes used to find sustained growth rather than a single short impulse.", trendWindow),
                settingRow("Minimum trend return", "Minimum net percentage growth required across the trend window.", trendReturn),
                settingRow("Trend efficiency", "Net progress divided by total travelled path. Higher values require a smoother rise.", trendEfficiency)
            ),
            section("Confirmation and data quality",
                settingRow("Volume confirmation Z", "Unusual log-volume confirmation; price movement remains mandatory.", volumeZ),
                settingRow("Relative volume", "Current candle volume divided by its robust local median.", relativeVolume),
                settingRow("Minimum source price", "Low-priced instruments below this value are ignored.", price),
                settingRow("Minimum session turnover", "Set to zero to keep the broadest statistical candidate base.", turnover),
                settingRow("Historical sessions", "Local sessions used to establish each instrument's normal behaviour.", sessions)
            ),
            section("Refresh and presentation",
                settingRow("Scan interval", "Seconds between complete scans; 180 is quota-friendly.", scanInterval),
                settingRow("Display currency", "Prices and turnover are converted only for presentation.", currency),
                settingRow("Finnhub live feed", "Optional. A new key is stored locally; blank keeps the existing configuration.", finnhubApiKey)
            ),
            section("Candidate universe",
                settingRow("Yahoo symbols",
                    "Comma-separated tickers scanned by Yahoo. Restore Defaults reinstates the standard liquid US and European universe.",
                    symbols.apply { prefRowCount = 5; maxHeight = 130.0 })
            ),
            Label("Only fresh directional price impulses are ranked. Volume alone cannot qualify a symbol. Historical Yahoo bars and live Finnhub bars are retained in SQLite.").apply {
                isWrapText = true; styleClass += "settings-footnote"
            }
        ).apply { padding = Insets(18.0) }

        val providers = VBox(14.0,
            Label("Optional website collectors run independently from Yahoo and Finnhub. Each provider stores its observations in a separate database series.").apply {
                isWrapText = true; styleClass += "settings-footnote"
            },
            section(
                "Lang & Schwarz",
                settingRow(
                    "Personal-use quote fallback",
                    "Disabled by default. Reads public bid/ask snapshots only when Scalable is unavailable. Enable only when your use complies with the website terms.",
                    langSchwarzEnabled
                )
            ),
            section("Tradegate",
                settingRow("Public quote collector", "Resolve company names to Tradegate instruments and collect EUR quotes during its weekday trading session.", tradegateEnabled),
                settingRow("Request interval", "Milliseconds between sequential instruments. A small timing jitter and automatic backoff are applied.", tradegateInterval)
            ),
            section("Euronext",
                settingRow("Public quote collector", "Resolve ISIN and MIC through Euronext search and collect delayed market-information quotes.", euronextEnabled),
                settingRow("Request interval", "Milliseconds between sequential instruments. A small timing jitter and automatic backoff are applied.", euronextInterval)
            ),
            section("External stock chart",
                settingRow("Stock search page",
                    "Open Stock uses this HTTPS search page. Saving verifies that a Northern Data query returns a stock result.",
                    stockSearchUrl)
            ),
            Label("Collectors honor Retry-After responses, pause after access or throttling errors, and do not replace newer database observations with older quotes.").apply {
                isWrapText = true; styleClass += "settings-footnote"
            }
        ).apply { padding = Insets(18.0) }

        val appearance = VBox(14.0,
            section(
                "Workspace",
                settingRow("Theme", "Light or dark colours for the main workspace.", theme),
                settingRow("Density", "Compact maximizes visible market data; Comfortable adds spacing.", density)
            ),
            section("Typography",
                settingRow("System font", "Use a light macOS system face or another installed family.", fontFamily),
                settingRow("Text size", "Scanner table font size in points.", fontSize)
            ),
            section("Table colours",
                settingRow("Text", "Primary table text colour.", textColor),
                settingRow("Even rows", "Background for alternating even rows.", evenRowColor),
                settingRow("Odd rows", "Background for alternating odd rows.", oddRowColor),
                settingRow("Selection", "Selected and hover highlight colour.", selectionColor),
                settingRow("Dividers", "Column and row separator colour.", gridColor)
            ),
            Label("Column order and width remain directly adjustable by dragging the table headers.").apply {
                isWrapText = true; styleClass += "settings-footnote"
            }
        ).apply { padding = Insets(18.0) }
        dialog.dialogPane.content = TabPane(
            Tab("Scanner", ScrollPane(scanner).apply { isFitToWidth = true; styleClass += "settings-scroll" }).apply { isClosable = false },
            Tab("Market Data Providers", ScrollPane(providers).apply { isFitToWidth = true; styleClass += "settings-scroll" }).apply { isClosable = false },
            Tab("Appearance", ScrollPane(appearance).apply { isFitToWidth = true; styleClass += "settings-scroll" }).apply { isClosable = false }
        )
        dialog.dialogPane.prefWidth = DEFAULT_WIDTH
        dialog.dialogPane.prefHeight = DEFAULT_HEIGHT
        dialog.dialogPane.buttonTypes += listOf(restoreDefaults, ButtonType.CANCEL, saveSettings)
        dialog.dialogPane.lookupButton(restoreDefaults).addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            event.consume()
            applyDefaults()
        }
        dialog.setResultConverter { null }
        dialog.dialogPane.lookupButton(saveSettings).addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            event.consume()
            val result = parse() ?: return@addEventFilter
            val saveButton = dialog.dialogPane.lookupButton(saveSettings)
            saveButton.isDisable = true
            CompletableFuture.runAsync { validateStockSearchUrl(result.criteria.stockSearchUrl) }.whenComplete { _, error ->
                Platform.runLater {
                    saveButton.isDisable = false
                    if (error == null) {
                        dialog.result = result
                        dialog.close()
                    } else Alert(Alert.AlertType.ERROR,
                        "Stock search URL is not relevant: searching for Northern Data did not return a stock page.",
                        ButtonType.OK).showAndWait()
                }
            }
        }
        geometry.attach(dialog)
    }

    private fun section(title: String, vararg content: javafx.scene.Node): VBox = VBox(10.0).apply {
        styleClass += "settings-glass-card"
        children += Label(title).apply { styleClass += "settings-section-title" }
        children += content
    }

    private fun settingRow(title: String, detail: String, control: Control): HBox = HBox(12.0).apply {
        alignment = javafx.geometry.Pos.CENTER_LEFT
        val description = HBox(6.0,
            Label(title).apply { styleClass += "settings-row-title" },
            helpButton(title, detail)
        ).apply {
            alignment = javafx.geometry.Pos.CENTER_LEFT
            minWidth = 260.0; prefWidth = 300.0; maxWidth = 330.0
        }
        control.minWidth = 180.0
        control.maxWidth = Double.MAX_VALUE
        HBox.setHgrow(control, Priority.ALWAYS)
        children += listOf(description, control)
    }

    private fun helpButton(title: String, detail: String): Button = Button("i").apply {
        styleClass += "settings-info-button"
        isFocusTraversable = false
        tooltip = Tooltip(detail)
        accessibleText = "$title information"
        setOnAction {
            val anchor = this
            val message = Label(detail).apply {
                isWrapText = true
                maxWidth = 300.0
                styleClass += "settings-info-content"
            }
            ContextMenu(CustomMenuItem(message, false)).apply {
                styleClass += "settings-info-popup"
                show(anchor, Side.BOTTOM, 0.0, 4.0)
            }
        }
    }

    private fun applyDefaults() {
        val defaults = ScannerCriteria()
        marketRegion.value = defaults.marketRegion
        scanInterval.valueFactory.value = defaults.scanIntervalSeconds.toInt()
        resultLimit.valueFactory.value = defaults.resultLimit
        price.valueFactory.value = defaults.minPrice
        turnover.valueFactory.value = defaults.minSessionTurnover
        sessions.valueFactory.value = defaults.baselineSessions
        signalAge.valueFactory.value = defaults.maxSignalAgeMinutes
        jumpZ.valueFactory.value = defaults.minJumpZ
        rangeZ.valueFactory.value = defaults.minRangeZ
        volumeZ.valueFactory.value = defaults.minVolumeZ
        relativeVolume.valueFactory.value = defaults.minRelativeVolume
        bodyRatio.valueFactory.value = defaults.minBodyRatio
        absoluteMove.valueFactory.value = defaults.minAbsoluteMovePercent
        minimumResults.valueFactory.value = defaults.minimumTableResults
        trendWindow.valueFactory.value = defaults.trendWindowMinutes
        trendReturn.valueFactory.value = defaults.minTrendReturnPercent
        trendEfficiency.valueFactory.value = defaults.minTrendEfficiency
        currency.value = defaults.displayCurrency
        langSchwarzEnabled.isSelected = defaults.langSchwarzEnabled
        tradegateEnabled.isSelected = defaults.tradegateEnabled
        tradegateInterval.valueFactory.value = defaults.tradegateRequestIntervalMillis.toInt()
        euronextEnabled.isSelected = defaults.euronextEnabled
        euronextInterval.valueFactory.value = defaults.euronextRequestIntervalMillis.toInt()
        stockSearchUrl.text = defaults.stockSearchUrl
        symbols.text = defaults.symbols.joinToString(", ")
        theme.value = defaults.tableAppearance.theme
        density.value = defaults.tableAppearance.density
        fontFamily.value = defaults.tableAppearance.fontFamily
        fontSize.valueFactory.value = defaults.tableAppearance.fontSize
        textColor.value = Color.web(defaults.tableAppearance.textColor)
        evenRowColor.value = Color.web(defaults.tableAppearance.evenRowColor)
        oddRowColor.value = Color.web(defaults.tableAppearance.oddRowColor)
        selectionColor.value = Color.web(defaults.tableAppearance.selectionColor)
        gridColor.value = Color.web(defaults.tableAppearance.gridColor)
    }

    fun showAndWait(): ScannerSettingsResult? = dialog.showAndWait().orElse(null)

    private fun parse(): ScannerSettingsResult? = runCatching {
        val criteria = ScannerCriteria(anomalyWindow = AnomalyWindow.HOUR, marketRegion = marketRegion.value,
            scanIntervalSeconds = scanInterval.value.toLong(),
            resultLimit = resultLimit.value,
            minPrice = price.value, minSessionTurnover = turnover.value,
            baselineSessions = sessions.value,
            maxSignalAgeMinutes = signalAge.value,
            minJumpZ = jumpZ.value,
            minRangeZ = rangeZ.value,
            minVolumeZ = volumeZ.value,
            minRelativeVolume = relativeVolume.value,
            minBodyRatio = bodyRatio.value,
            minAbsoluteMovePercent = absoluteMove.value,
            minimumTableResults = minimumResults.value,
            trendWindowMinutes = trendWindow.value,
            minTrendReturnPercent = trendReturn.value,
            minTrendEfficiency = trendEfficiency.value,
            displayCurrency = currency.value,
            langSchwarzEnabled = langSchwarzEnabled.isSelected,
            tradegateEnabled = tradegateEnabled.isSelected,
            tradegateRequestIntervalMillis = tradegateInterval.value.toLong(),
            euronextEnabled = euronextEnabled.isSelected,
            euronextRequestIntervalMillis = euronextInterval.value.toLong(),
            stockSearchUrl = stockSearchUrl.text.trim().also { require(it.isNotEmpty()) },
            tableAppearance = TableAppearance(
                theme = theme.value ?: UiTheme.LIGHT,
                density = density.value ?: UiDensity.COMPACT,
                fontFamily = fontFamily.value ?: "SF Pro Display",
                fontSize = fontSize.value,
                textColor = hex(textColor.value),
                evenRowColor = hex(evenRowColor.value),
                oddRowColor = hex(oddRowColor.value),
                selectionColor = hex(selectionColor.value),
                gridColor = hex(gridColor.value)
            ),
            symbols = service.normalizeSymbols(symbols.text).also { require(it.isNotEmpty()) })
        ScannerSettingsResult(criteria, finnhubApiKey.text.trim().takeIf(String::isNotEmpty))
    }.onFailure { Alert(Alert.AlertType.ERROR, "Check numeric values and enter at least one symbol.", ButtonType.OK).showAndWait() }.getOrNull()

    private fun hex(color: Color): String = "#%02X%02X%02X".format(
        (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
    )

    private companion object {
        const val DEFAULT_WIDTH = 897.0
        const val DEFAULT_HEIGHT = 720.0
    }
}