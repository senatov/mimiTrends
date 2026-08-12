package org.senatov.mimitrends

import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyLongWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.senatov.mimitrends.model.CompanyProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ShortMovePanel(
    private val onOpen: (String) -> Unit,
    savedColumns: String = "",
    private val loadProfile: ((String) -> java.util.concurrent.CompletableFuture<CompanyProfile>)? = null
) : VBox(5.0) {
    private val rows = FXCollections.observableArrayList<ShortMove>()
    private val table = TableView(rows)
    private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val companyNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val columnLayout: TableColumnLayout<ShortMove>
    private val autoFitter: TableColumnAutoFitter<ShortMove>

    init {
        val spacer = javafx.scene.layout.Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val header = HBox(8.0,
            Label("Recent price battles").apply { styleClass += "table-section-title" }, spacer,
            Label("5-minute moves + post-drop struggle · top 10").apply { styleClass += "short-move-caption" }
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "table-section-header"
        }
        val company = TableColumn<ShortMove, String>("Company").apply {
            id = "company"
            setCellValueFactory { ReadOnlyStringWrapper(companyNames[it.value.symbol] ?: it.value.symbol) }
            prefWidth = 210.0; minWidth = 90.0
        }
        val direction = TableColumn<ShortMove, String>("Direction").apply {
            id = "direction"
            setCellValueFactory { ReadOnlyStringWrapper(directionLabel(it.value)) }
            setCellFactory { DirectionCell() }; prefWidth = 105.0
        }
        val move = TableColumn<ShortMove, Number>("Move").apply {
            id = "move"
            setCellValueFactory { ReadOnlyDoubleWrapper(it.value.changePercent) }
            setCellFactory { PercentCell() }; prefWidth = 105.0
        }
        val period = TableColumn<ShortMove, Number>("Period").apply {
            id = "period"
            setCellValueFactory { ReadOnlyLongWrapper(it.value.endedAtEpochSeconds) }
            setCellFactory { object : TableCell<ShortMove, Number>() {
                override fun updateItem(item: Number?, empty: Boolean) {
                    super.updateItem(item, empty)
                    val row = tableRow?.item
                    text = if (empty || item == null || row == null) null else
                        "${time.format(Instant.ofEpochSecond(row.startedAtEpochSeconds))}–${time.format(Instant.ofEpochSecond(item.toLong()))}"
                }
            } }
            prefWidth = 135.0
        }
        table.columns.setAll(company, direction, move, period)
        columnLayout = TableColumnLayout(table, savedColumns).also(TableColumnLayout<ShortMove>::install)
        autoFitter = TableColumnAutoFitter(table, listOf(
            TableColumnAutoFitter.Spec(company, { companyNames[it.symbol] ?: it.symbol }, 80.0, 240.0),
            TableColumnAutoFitter.Spec(direction, ::directionLabel, 68.0, 105.0),
            TableColumnAutoFitter.Spec(move, { "%+.2f%%".format(it.changePercent) }, 54.0, 76.0),
            TableColumnAutoFitter.Spec(period, {
                "${time.format(Instant.ofEpochSecond(it.startedAtEpochSeconds))}–${time.format(Instant.ofEpochSecond(it.endedAtEpochSeconds))}"
            }, 82.0, 105.0)
        ), columnLayout.savedWidths())
        table.placeholder = Label("Waiting for recent minute bars…")
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.fixedCellSize = 23.0
        VBox.setVgrow(table, Priority.ALWAYS)
        table.styleClass += listOf("scanner-table", "short-move-table")
        table.setRowFactory {
            TableRow<ShortMove>().apply {
                setOnMouseClicked { event ->
                    if (!isEmpty && event.button == MouseButton.PRIMARY && event.clickCount == 1) onOpen(item.symbol)
                }
            }
        }
        styleClass += "table-section"
        children.setAll(header, table)
    }

    internal fun show(moves: Collection<ShortMove>) {
        rows.setAll(moves)
        moves.forEach(::requestCompanyName)
        autoFitter.request()
    }

    internal fun savedColumnLayout(): String = columnLayout.capture()

    private fun directionLabel(move: ShortMove): String = when (move.pattern) {
        ShortMovePattern.POST_DROP_STRUGGLE -> "◆ POST-DROP"
        ShortMovePattern.DIRECTIONAL -> if (move.changePercent >= 0.0) "▲ UP" else "▼ DOWN"
    }

    private fun requestCompanyName(move: ShortMove) {
        if (companyNames.containsKey(move.symbol)) return
        companyNames[move.symbol] = move.symbol
        loadProfile?.invoke(move.symbol)?.whenComplete { profile, error ->
            if (error == null && profile != null) javafx.application.Platform.runLater {
                companyNames[move.symbol] = CompanySearchTerm.normalizeDisplay(profile.name)
                table.refresh()
                autoFitter.request()
            }
        }
    }

    private class DirectionCell : TableCell<ShortMove, String>() {
        override fun updateItem(item: String?, empty: Boolean) {
            super.updateItem(item, empty); text = if (empty) null else item
            styleClass.removeAll("short-move-up", "short-move-down", "short-move-struggle")
            if (!empty && item != null) styleClass += when {
                item.contains("POST-DROP") -> "short-move-struggle"
                item.contains("UP") -> "short-move-up"
                else -> "short-move-down"
            }
        }
    }

    private class PercentCell : TableCell<ShortMove, Number>() {
        override fun updateItem(item: Number?, empty: Boolean) {
            super.updateItem(item, empty); text = if (empty || item == null) null else "%+.2f%%".format(item.toDouble())
            styleClass.removeAll("short-move-up", "short-move-down")
            if (!empty && item != null) styleClass += if (item.toDouble() >= 0.0) "short-move-up" else "short-move-down"
        }
    }
}
