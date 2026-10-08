package org.senatov.mimitrends.shortmove

import javafx.scene.control.TableCell
import javafx.scene.control.Tooltip
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.MouseEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.geometry.Pos
import java.time.Instant
import java.util.Locale
import org.senatov.mimitrends.model.CurveDecision
import org.senatov.mimitrends.model.CurveDirection

internal fun shortMoveAlertPriority(move: ShortMove): Int =
    if (move.reviewDecision == CurveDecision.PENDING) 2 else when (move.pattern) {
    ShortMovePattern.RAPID_CRASH -> 0
    ShortMovePattern.RAPID_RISE -> 1
        ShortMovePattern.TRADABLE_CORRIDOR -> 3
}

internal fun shortMoveDirectionLabel(move: ShortMove): String = if (move.reviewDecision == CurveDecision.PENDING) {
    if (move.curveCandidate?.direction == CurveDirection.RISE) "? REVIEW RISE" else "? REVIEW DROP"
} else when (move.pattern) {
    ShortMovePattern.RAPID_CRASH -> retainedLabel(move, "‼ RAPID CRASH")
    ShortMovePattern.RAPID_RISE -> retainedLabel(move, "↑ RAPID RISE")
    ShortMovePattern.TRADABLE_CORRIDOR -> retainedLabel(move, "▰ CORRIDOR")
}

private fun retainedLabel(move: ShortMove, activeLabel: String): String =
    if (move.isRetained) "$activeLabel · RECENT" else activeLabel

internal class ShortMoveDirectionCell(
    private val showRumors: (Button, ShortMove) -> Unit
) : TableCell<ShortMove, ShortMove>() {
    override fun updateItem(item: ShortMove?, empty: Boolean) {
        super.updateItem(item, empty)
        val label = item?.let(::shortMoveDirectionLabel)
        text = null
        graphic = if (empty || item == null || label == null) null else HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            prefWidthProperty().bind(this@ShortMoveDirectionCell.widthProperty().subtract(12.0))
            children += Label(label).apply { styleClass += "short-move-event-label" }
            children += Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
            val button = Button("Rumors").apply {
                styleClass += "rumors-button"
                minWidth = javafx.scene.layout.Region.USE_PREF_SIZE
                addEventFilter(MouseEvent.MOUSE_CLICKED) { it.consume() }
                setOnAction { showRumors(this, item) }
            }
            children += button
        }
        styleClass.removeAll(
            "short-move-up", "short-move-down", "short-move-struggle",
            "rapid-crash-cell"
        )
        if (!empty && item != null && label != null) {
            if (item.pattern == ShortMovePattern.RAPID_CRASH) {
                styleClass += "rapid-crash-cell"
            } else if (item.pattern == ShortMovePattern.RAPID_RISE) {
                styleClass += "short-move-up"
            } else {
                styleClass += "short-move-down"
            }
        }
    }
}

internal class ShortMoveMovementCell : TableCell<ShortMove, ShortMove>() {
    override fun updateItem(item: ShortMove?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty || item == null) null else ShortMovePresentation.movement(item)
        tooltip = if (empty || item == null) null else Tooltip(ShortMovePresentation.details(item))
        styleClass.removeAll("short-move-up", "short-move-down")
        if (!empty && item != null) styleClass += when (item.pattern) {
            ShortMovePattern.RAPID_CRASH -> "short-move-down"
            ShortMovePattern.RAPID_RISE -> "short-move-up"
            ShortMovePattern.TRADABLE_CORRIDOR -> "short-move-up"
        }
    }
}

internal class ShortMoveAgeCell : TableCell<ShortMove, ShortMove>() {
    override fun updateItem(item: ShortMove?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty || item == null) null else ShortMovePresentation.age(item, Instant.now().epochSecond)
        tooltip = if (empty || item == null) null else Tooltip(
            if (item.isRetained) "Recent alert; no longer confirmed by the latest scan."
            else "Time since the latest confirmed event observation."
        )
    }
}

internal class ShortMoveCurrentPriceCell : TableCell<ShortMove, Number>() {
    override fun updateItem(item: Number?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty || item == null) null else String.format(Locale.ROOT, "%,.2f", item.toDouble())
    }
}