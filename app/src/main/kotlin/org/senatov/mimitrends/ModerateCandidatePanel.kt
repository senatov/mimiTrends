package org.senatov.mimitrends

import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.input.MouseButton
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.senatov.mimitrends.model.CompanyProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal class ModerateCandidatePanel(
    private val onOpen: (String, Long) -> Unit,
    private val loadProfile: ((String) -> CompletableFuture<CompanyProfile>)? = null
) : VBox(5.0) {
    private val rows = FXCollections.observableArrayList<ShortMove>()
    private val table = TableView(rows)
    private val names = ConcurrentHashMap<String, String>()
    private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    init {
        val company = TableColumn<ShortMove, String>("Company").apply {
            setCellValueFactory { ReadOnlyStringWrapper(names[it.value.symbol] ?: it.value.symbol) }
            prefWidth = 155.0
        }
        val move = TableColumn<ShortMove, Number>("Move").apply {
            setCellValueFactory { ReadOnlyDoubleWrapper(it.value.changePercent) }
            setCellFactory {
                object : TableCell<ShortMove, Number>() {
                    override fun updateItem(item: Number?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = item?.takeUnless { empty }?.let { "%+.2f%%".format(it.toDouble()) }
                    }
                }
            }
            prefWidth = 70.0
        }
        val updated = TableColumn<ShortMove, String>("At").apply {
            setCellValueFactory { ReadOnlyStringWrapper(time.format(Instant.ofEpochSecond(it.value.endedAtEpochSeconds))) }
            prefWidth = 58.0
        }
        table.columns.setAll(company, move, updated)
        table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        table.placeholder = Label("No moderate candidates")
        table.styleClass += listOf("scanner-table", "moderate-candidate-table")
        table.setRowFactory {
            javafx.scene.control.TableRow<ShortMove>().apply {
                setOnMouseClicked { event ->
                    if (!isEmpty && event.button == MouseButton.PRIMARY && event.clickCount == 1) {
                        onOpen(item.symbol, item.endedAtEpochSeconds)
                    }
                }
            }
        }
        children.setAll(
            Label("Steady positive").apply {
                alignment = Pos.CENTER_LEFT
                styleClass += "table-section-title"
            },
            Label("Moderate rise or holding gains").apply { styleClass += "short-move-caption" },
            table
        )
        styleClass += listOf("table-section", "moderate-candidate-panel")
        VBox.setVgrow(table, Priority.ALWAYS)
        minWidth = 250.0
        prefWidth = 285.0
        maxWidth = 330.0
    }

    fun show(moves: Collection<ShortMove>) {
        val selected = ModeratePositiveCandidateSelector.select(moves.toList()).take(MAX_CANDIDATES)
        rows.setAll(selected)
        selected.forEach(::requestName)
    }

    private fun requestName(move: ShortMove) {
        if (names.putIfAbsent(move.symbol, move.symbol) != null) return
        loadProfile?.invoke(move.symbol)?.whenComplete { profile, error ->
            if (error == null && profile != null) javafx.application.Platform.runLater {
                names[move.symbol] = CompanySearchTerm.normalizeDisplay(profile.name)
                table.refresh()
            }
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 6
    }
}
