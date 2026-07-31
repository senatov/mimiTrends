package org.senatov.mimitrends

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.stage.Window
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.TableAppearance
import javafx.collections.FXCollections
import javafx.scene.paint.Color
import javafx.scene.text.Font
import org.senatov.mimitrends.scanner.ScannerSettingsService

class ScannerSettingsDialog(owner: Window?, current: ScannerCriteria, private val service: ScannerSettingsService) {
    private val dialog = Dialog<ScannerCriteria>()
    private val rvol = TextField(current.minRelativeVolume.toString())
    private val one = TextField(current.minChange1mPercent.toString())
    private val five = TextField(current.minChange5mPercent.toString())
    private val price = TextField(current.minPrice.toString())
    private val volume = TextField(current.minSessionVolume.toString())
    private val sessions = TextField(current.baselineSessions.toString())
    private val batchSize = TextField(current.batchSize.toString())
    private val rotationSeconds = TextField(current.rotationSeconds.toString())
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
        owner?.let(dialog::initOwner); dialog.title = "Scanner Settings"; dialog.headerText = "Momentum filter (strictly greater than)"
        val grid = GridPane().apply { hgap = 12.0; vgap = 9.0; padding = Insets(12.0) }
        listOf("Relative volume" to rvol, "Change 1m, %" to one, "Change 5m, %" to five, "Minimum source price" to price,
            "Session volume" to volume, "Baseline sessions" to sessions, "Symbols per batch (max. 50)" to batchSize,
            "Batch duration, seconds" to rotationSeconds, "Display currency" to currency,
            "Symbols (no total limit)" to symbols).forEachIndexed { row, pair ->
            grid.add(Label(pair.first), 0, row); grid.add(pair.second, 1, row)
        }
        grid.add(Label("Symbols are scanned cyclically in batches. RVOL = 0 disables that filter while SQLite accumulates its baseline; positive values require a calculated RVOL. Currency changes presentation only.").apply { isWrapText = true; maxWidth = 430.0 }, 0, 10, 2, 1)
        val appearance = GridPane().apply { hgap = 14.0; vgap = 11.0; padding = Insets(12.0) }
        listOf(
            "Font" to fontFamily,
            "Font size" to fontSize,
            "Text" to textColor,
            "Even rows" to evenRowColor,
            "Odd rows" to oddRowColor,
            "Selection" to selectionColor,
            "Dividers" to gridColor
        ).forEachIndexed { row, pair -> appearance.add(Label(pair.first), 0, row); appearance.add(pair.second, 1, row) }
        appearance.add(Label("Column widths remain adjustable by dragging the header dividers.").apply {
            isWrapText = true; maxWidth = 390.0
        }, 0, 7, 2, 1)
        dialog.dialogPane.content = TabPane(
            Tab("Scanner", grid).apply { isClosable = false },
            Tab("Table appearance", appearance).apply { isClosable = false }
        )
        dialog.dialogPane.prefWidth = 570.0
        dialog.dialogPane.buttonTypes += listOf(ButtonType.CANCEL, ButtonType("Save", ButtonBar.ButtonData.OK_DONE))
        dialog.setResultConverter { if (it.buttonData == ButtonBar.ButtonData.OK_DONE) parse() else null }
    }

    fun showAndWait(): ScannerCriteria? = dialog.showAndWait().orElse(null)

    private fun parse(): ScannerCriteria? = runCatching {
        ScannerCriteria(minRelativeVolume = rvol.text.toDouble(), minChange1mPercent = one.text.toDouble(),
            minChange5mPercent = five.text.toDouble(), minPrice = price.text.toDouble(), minSessionVolume = volume.text.toDouble(),
            baselineSessions = sessions.text.toInt().coerceIn(3, 100), batchSize = batchSize.text.toInt().coerceIn(1, 50),
            rotationSeconds = rotationSeconds.text.toLong().coerceIn(5, 3600),
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
