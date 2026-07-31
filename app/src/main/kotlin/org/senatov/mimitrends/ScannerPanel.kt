package org.senatov.mimitrends

import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.FXCollections
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.DisplayCurrency
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ScannerPanel(private val onOpen: (String) -> Unit) : VBox(7.0) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rows = FXCollections.observableArrayList<ScanResult>()
    private val table = TableView(rows)
    private val empty = Label("Waiting for matching WebSocket trades…")
    private val cycleStatus = Label()
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private var currency = DisplayCurrency.EUR
    private var convertPrice: (String, Double) -> Double = { _, value -> value }

    init {
        log.debug(LogTag.UI, "init()")
        val header = javafx.scene.layout.HBox(8.0, Label("Momentum scanner").apply { styleClass += "scanner-title" }, cycleStatus.apply { styleClass += "scanner-cycle" },
            javafx.scene.layout.Region().also { javafx.scene.layout.HBox.setHgrow(it, Priority.ALWAYS) })
        column("Symbol") { it.symbol }
        column("Price") { "${currency.symbol}%,.2f".format(convertPrice(it.symbol, it.price)) }
        column("RVOL") { it.relativeVolume?.let { v -> "%.2f×".format(v) } ?: "N/A" }
        column("Δ 1m") { percent(it.change1mPercent) }
        column("Δ 5m") { percent(it.change5mPercent) }
        column("Volume") { "%,.0f".format(it.sessionVolume) }
        column("Updated") { time.format(Instant.ofEpochMilli(it.updatedAtMillis)) }
        table.placeholder = empty
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.setRowFactory {
            TableRow<ScanResult>().apply { setOnMouseClicked { e -> if (!isEmpty && e.button == MouseButton.PRIMARY && e.clickCount == 1) onOpen(item.symbol) } }
        }
        table.minHeight = 0.0
        table.maxHeight = Double.MAX_VALUE
        table.styleClass += "scanner-table"
        children += listOf(header, table)
        VBox.setVgrow(table, Priority.ALWAYS)
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
    }

    fun update(result: ScanResult) {
        log.debug(LogTag.UI, "update(symbol={}, matches={})", result.symbol, result.matches)
        rows.removeIf { it.symbol == result.symbol }
        if (result.matches) rows += result
        rows.sortByDescending { it.relativeVolume ?: 0.0 }
    }

    fun clear() { log.debug(LogTag.UI, "clear()"); rows.clear() }

    fun showBatch(number: Int, total: Int, symbols: List<String>, seconds: Long) {
        log.debug(LogTag.UI, "showBatch(number={}, total={}, symbols={})", number, total, symbols.size)
        cycleStatus.text = "Batch $number/$total · ${symbols.size} symbols · ${seconds}s"
        cycleStatus.tooltip = Tooltip(symbols.joinToString(", "))
    }

    fun setCurrency(value: DisplayCurrency, converter: (String, Double) -> Double) {
        log.debug(LogTag.UI, "setCurrency(currency={})", value)
        currency = value; convertPrice = converter; table.refresh()
    }

    private fun column(title: String, value: (ScanResult) -> String) {
        log.debug(LogTag.UI, "column(title={})", title)
        table.columns += TableColumn<ScanResult, String>(title).apply {
            setCellValueFactory { ReadOnlyObjectWrapper(value(it.value)) }
            isResizable = true
            isReorderable = true
            prefWidth = when (title) {
                "Symbol" -> 105.0
                "Volume" -> 125.0
                "Updated" -> 105.0
                else -> 115.0
            }
            minWidth = 55.0
        }
    }

    private fun percent(value: Double?) = value?.let { "%+.2f%%".format(it) } ?: "N/A"
}
