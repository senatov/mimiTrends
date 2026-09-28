package org.senatov.mimitrends.providers

import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.Duration

internal class SourceActivityPanel(private val activity: SourceActivity) : VBox(5.0) {
    private val rows = FXCollections.observableArrayList<SourceActivitySnapshot>()
    private val table = TableView(rows)
    private val timer = Timeline(KeyFrame(Duration.seconds(5.0), javafx.event.EventHandler { refresh() })).apply {
        cycleCount = Timeline.INDEFINITE
    }

    init {
        val source = TableColumn<SourceActivitySnapshot, String>("Source").apply {
            setCellValueFactory { ReadOnlyStringWrapper(it.value.source) }
            minWidth = 108.0
            prefWidth = 135.0
        }
        val last = TableColumn<SourceActivitySnapshot, SourceActivitySnapshot>("Last").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            minWidth = 72.0
            prefWidth = 90.0
            setCellFactory {
                object : TableCell<SourceActivitySnapshot, SourceActivitySnapshot>() {
                    override fun updateItem(item: SourceActivitySnapshot?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else item.status ?: age(item.lastContactMillis)
                        styleClass.remove("source-failed-age")
                        if (!empty && item?.failed == true) styleClass += "source-failed-age"
                        tooltip = item?.let { snapshot ->
                            val contact = snapshot.lastContactMillis?.let { millis ->
                                java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toString()
                            }
                            listOfNotNull(snapshot.status, contact?.let { "Last contact: $it" })
                                .takeIf(List<String>::isNotEmpty)?.joinToString("\n")?.let(::Tooltip)
                        }
                    }
                }
            }
        }
        val counts = TableColumn<SourceActivitySnapshot, SourceActivitySnapshot>("Received (valid)").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            minWidth = 125.0
            prefWidth = 150.0
            setCellFactory {
                object : TableCell<SourceActivitySnapshot, SourceActivitySnapshot>() {
                    override fun updateItem(item: SourceActivitySnapshot?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = null
                        graphic = if (empty || item?.processed == null) null else HBox(
                            3.0,
                            Label(item.processed.toString()),
                            Label("(${item.accepted})").apply { styleClass += "source-selected-count" }
                        ).apply { alignment = Pos.CENTER_LEFT }
                        if (item?.failed == true && !empty) {
                            tooltip = Tooltip("Latest source operation failed")
                        } else if (!empty && item != null) {
                            tooltip = Tooltip("Latest source operation: ${unit(item.source)} received, " +
                                "${item.accepted} valid. Units differ by source; this is not the signal filter.")
                        } else tooltip = null
                    }
                }
            }
        }
        table.columns.setAll(source, last, counts)
        table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        table.fixedCellSize = 30.0
        table.isFocusTraversable = false
        table.selectionModel.isCellSelectionEnabled = false
        table.styleClass += listOf("scanner-table", "source-activity-table")
        VBox.setVgrow(table, Priority.ALWAYS)
        styleClass += listOf("table-section", "source-activity-panel")
        children.setAll(
            HBox(Label("Sources").apply { styleClass += "table-section-title" }).apply {
                styleClass += "table-section-header"
            },
            table
        )
        minWidth = 320.0
        refresh()
        sceneProperty().addListener { _, _, scene -> if (scene == null) timer.stop() else timer.play() }
    }

    private fun refresh() {
        rows.setAll(activity.snapshot())
        table.refresh()
    }

    private fun age(lastMillis: Long?): String {
        if (lastMillis == null) return "—"
        val seconds = ((System.currentTimeMillis() - lastMillis).coerceAtLeast(0L) / 1_000L)
        return if (seconds < 60) "<1 min." else if (seconds < 3_600) "${seconds / 60} min."
        else if (seconds < 86_400) "${seconds / 3_600} h." else "${seconds / 86_400} d."
    }

    private fun unit(source: String): String = when (source) {
        "Yahoo" -> "minute bars"
        "Finnhub" -> "trades"
        "wallstreetONLINE", "TraderFox" -> "discovery entries"
        else -> "quotes"
    }
}
