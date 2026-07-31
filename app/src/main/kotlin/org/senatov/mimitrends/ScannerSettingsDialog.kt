package org.senatov.mimitrends

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.stage.Window
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.model.DisplayCurrency
import javafx.collections.FXCollections
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

    init {
        owner?.let(dialog::initOwner); dialog.title = "Scanner Settings"; dialog.headerText = "Momentum filter (strictly greater than)"
        val grid = GridPane().apply { hgap = 12.0; vgap = 9.0; padding = Insets(8.0) }
        listOf("Relative volume" to rvol, "Change 1m, %" to one, "Change 5m, %" to five, "Minimum price, $" to price,
            "Session volume" to volume, "Baseline sessions" to sessions, "Symbols per batch (max. 50)" to batchSize,
            "Batch duration, seconds" to rotationSeconds, "Display currency" to currency,
            "Symbols (no total limit)" to symbols).forEachIndexed { row, pair ->
            grid.add(Label(pair.first), 0, row); grid.add(pair.second, 1, row)
        }
        grid.add(Label("Symbols are scanned cyclically in batches. Currency changes presentation only; raw market data stays unchanged. RVOL is N/A until SQLite has at least 3 prior sessions.").apply { isWrapText = true; maxWidth = 430.0 }, 0, 10, 2, 1)
        dialog.dialogPane.content = grid; dialog.dialogPane.buttonTypes += listOf(ButtonType.CANCEL, ButtonType("Save", ButtonBar.ButtonData.OK_DONE))
        dialog.setResultConverter { if (it.buttonData == ButtonBar.ButtonData.OK_DONE) parse() else null }
    }

    fun showAndWait(): ScannerCriteria? = dialog.showAndWait().orElse(null)

    private fun parse(): ScannerCriteria? = runCatching {
        ScannerCriteria(minRelativeVolume = rvol.text.toDouble(), minChange1mPercent = one.text.toDouble(),
            minChange5mPercent = five.text.toDouble(), minPrice = price.text.toDouble(), minSessionVolume = volume.text.toDouble(),
            baselineSessions = sessions.text.toInt().coerceIn(3, 100), batchSize = batchSize.text.toInt().coerceIn(1, 50),
            rotationSeconds = rotationSeconds.text.toLong().coerceIn(5, 3600),
            displayCurrency = currency.value,
            symbols = service.normalizeSymbols(symbols.text).also { require(it.isNotEmpty()) })
    }.onFailure { Alert(Alert.AlertType.ERROR, "Check numeric values and enter at least one symbol.", ButtonType.OK).showAndWait() }.getOrNull()
}
