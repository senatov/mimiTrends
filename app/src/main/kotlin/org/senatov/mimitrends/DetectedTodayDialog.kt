package org.senatov.mimitrends

import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.stage.Window
import org.senatov.mimitrends.db.TodayDetection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object DetectedTodayDialog {
    fun show(owner: Window?, detections: List<TodayDetection>) {
        val table = TableView<TodayDetection>().apply {
            items.setAll(detections)
            placeholder = WorkspaceEmptyState.create(
                "No signals published today",
                "Published signals will be listed here as scanner cycles complete."
            )
            prefWidth = 760.0
            prefHeight = 430.0
        }
        table.columns.setAll(
            column("Symbol", 100.0) { it.symbol },
            column("Latest signal", 230.0) { it.signal },
            column("First", 90.0) { formatTime(it.firstDetectedEpochSeconds) },
            column("Last", 90.0) { formatTime(it.lastDetectedEpochSeconds) },
            column("Cycles", 70.0) { it.publishedCycles },
            column("Best score", 90.0) { "%.2f".format(it.bestScore) },
            column("Largest move", 100.0) { "%+.2f%%".format(it.largestMovePercent) }
        )
        Dialog<Unit>().apply {
            title = "Detected today"
            headerText = "Signals published at least once today · ${detections.size} symbols"
            initOwner(owner)
            dialogPane.content = table
            dialogPane.buttonTypes += ButtonType.CLOSE
            isResizable = true
        }.showAndWait()
    }

    private fun <T> column(title: String, width: Double, value: (TodayDetection) -> T) =
        TableColumn<TodayDetection, T>(title).apply {
            prefWidth = width
            setCellValueFactory { ReadOnlyObjectWrapper(value(it.value)) }
        }

    private fun formatTime(epochSeconds: Long): String = TIME.format(Instant.ofEpochSecond(epochSeconds))

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
}
