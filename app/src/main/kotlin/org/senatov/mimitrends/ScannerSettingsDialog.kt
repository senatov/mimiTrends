package org.senatov.mimitrends

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.TableAppearance
import org.senatov.mimitrends.model.AnomalyWindow
import org.senatov.mimitrends.model.MarketRegion
import javafx.collections.FXCollections
import javafx.scene.paint.Color
import javafx.scene.text.Font
import org.senatov.mimitrends.scanner.ScannerSettingsService

class ScannerSettingsDialog(owner: Window?, current: ScannerCriteria, private val service: ScannerSettingsService) {
    private val dialog = Dialog<ScannerCriteria>()
    private val marketRegion = ComboBox(FXCollections.observableArrayList(MarketRegion.entries)).apply { value = current.marketRegion }
    private val scanInterval = Spinner<Int>(60, 3_600, current.scanIntervalSeconds.toInt(), 30).apply { isEditable = true }
    private val resultLimit = Spinner<Int>(10, 100, current.resultLimit, 5).apply { isEditable = true }
    private val price = Spinner<Double>(0.0, 10_000.0, current.minPrice, 0.5).apply { isEditable = true }
    private val turnover = Spinner<Double>(0.0, 10_000_000_000.0, current.minSessionTurnover, 100_000.0).apply { isEditable = true }
    private val sessions = Spinner<Int>(3, 20, current.baselineSessions, 1).apply { isEditable = true }
    private val currency = ComboBox(FXCollections.observableArrayList(DisplayCurrency.entries)).apply { value = current.displayCurrency }
    private val symbols = TextArea(current.symbols.joinToString(", ")).apply { prefRowCount = 3; isWrapText = true }
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
        dialog.dialogPane.stylesheets += requireNotNull(javaClass.getResource("/org/senatov/mimitrends/MiMiTrends.css")).toExternalForm()
        dialog.dialogPane.styleClass += "glass-settings-dialog"

        val scanner = VBox(14.0,
            section("Signal detection",
                settingRow("Recent signal horizon", "Three rolling windows: now–5, 5–10 and 10–15 minutes.",
                    Label("0–15 minutes").apply { styleClass += "settings-value-badge" }),
                settingRow("Market universe", "Select US listings, European listings, or both.", marketRegion),
                settingRow("Results to display", "Highest current anomaly scores retained after a complete scan.", resultLimit),
                settingRow("Minimum source price", "Low-priced instruments below this value are ignored.", price),
                settingRow("Minimum session turnover", "Set to zero to keep the broadest statistical candidate base.", turnover),
                settingRow("Historical sessions", "Local sessions used to establish each instrument's normal behaviour.", sessions)
            ),
            section("Refresh and presentation",
                settingRow("Scan interval", "Seconds between complete scans; 180 is quota-friendly.", scanInterval),
                settingRow("Display currency", "Prices and turnover are converted only for presentation.", currency)
            ),
            section("Candidate universe",
                Label("Comma-separated Yahoo symbols. The expanded default universe contains 100 liquid US and European listings.").apply {
                    isWrapText = true; styleClass += "settings-help"
                },
                symbols.apply { prefRowCount = 5; maxHeight = 130.0 }
            ),
            Label("Ranking uses only activity found in the latest 15 minutes. Every downloaded minute bar is retained in SQLite and reused as the normal baseline.").apply {
                isWrapText = true; styleClass += "settings-footnote"
            }
        ).apply { padding = Insets(18.0) }

        val appearance = VBox(14.0,
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
            Tab("Appearance", ScrollPane(appearance).apply { isFitToWidth = true; styleClass += "settings-scroll" }).apply { isClosable = false }
        )
        dialog.dialogPane.prefWidth = 780.0
        dialog.dialogPane.prefHeight = 720.0
        dialog.dialogPane.buttonTypes += listOf(ButtonType.CANCEL, ButtonType("Save", ButtonBar.ButtonData.OK_DONE))
        dialog.setResultConverter { if (it.buttonData == ButtonBar.ButtonData.OK_DONE) parse() else null }
    }

    private fun section(title: String, vararg content: javafx.scene.Node): VBox = VBox(10.0).apply {
        styleClass += "settings-glass-card"
        children += Label(title).apply { styleClass += "settings-section-title" }
        children += content
    }

    private fun settingRow(title: String, detail: String, control: Control): HBox = HBox(18.0).apply {
        alignment = javafx.geometry.Pos.CENTER_LEFT
        val description = VBox(2.0,
            Label(title).apply { styleClass += "settings-row-title" },
            Label(detail).apply { isWrapText = true; styleClass += "settings-row-detail" }
        ).apply { minWidth = 330.0; prefWidth = 390.0; maxWidth = 440.0 }
        control.minWidth = 180.0
        control.maxWidth = Double.MAX_VALUE
        HBox.setHgrow(control, Priority.ALWAYS)
        children += listOf(description, control)
    }

    fun showAndWait(): ScannerCriteria? = dialog.showAndWait().orElse(null)

    private fun parse(): ScannerCriteria? = runCatching {
        ScannerCriteria(anomalyWindow = AnomalyWindow.HOUR, marketRegion = marketRegion.value,
            scanIntervalSeconds = scanInterval.value.toLong(),
            resultLimit = resultLimit.value,
            minPrice = price.value, minSessionTurnover = turnover.value,
            baselineSessions = sessions.value,
            displayCurrency = currency.value,
            tableAppearance = TableAppearance(
                fontFamily = fontFamily.value ?: "SF Pro Display",
                fontSize = fontSize.value,
                textColor = hex(textColor.value),
                evenRowColor = hex(evenRowColor.value),
                oddRowColor = hex(oddRowColor.value),
                selectionColor = hex(selectionColor.value),
                gridColor = hex(gridColor.value)
            ),
            symbols = service.normalizeSymbols(symbols.text).also { require(it.isNotEmpty()) })
    }.onFailure { Alert(Alert.AlertType.ERROR, "Check numeric values and enter at least one symbol.", ButtonType.OK).showAndWait() }.getOrNull()

    private fun hex(color: Color): String = "#%02X%02X%02X".format(
        (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()
    )
}
