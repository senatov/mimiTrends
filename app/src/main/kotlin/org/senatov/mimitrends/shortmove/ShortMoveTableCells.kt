package org.senatov.mimitrends.shortmove

import javafx.scene.control.TableCell
import javafx.scene.control.Tooltip
import java.time.Instant
import java.util.Locale

internal fun shortMoveAlertPriority(move: ShortMove): Int = when (move.pattern) {
    ShortMovePattern.RAPID_CRASH -> 0
    ShortMovePattern.TRADABLE_CORRIDOR -> 1
}

internal fun shortMoveDirectionLabel(move: ShortMove): String = when (move.pattern) {
    ShortMovePattern.RAPID_CRASH -> retainedLabel(move, "‼ RAPID CRASH")
    ShortMovePattern.TRADABLE_CORRIDOR -> retainedLabel(move, "▰ CORRIDOR")
}

private fun retainedLabel(move: ShortMove, activeLabel: String): String =
    if (move.isRetained) "$activeLabel · RECENT" else activeLabel

internal class ShortMoveDirectionCell : TableCell<ShortMove, ShortMove>() {
    override fun updateItem(item: ShortMove?, empty: Boolean) {
        super.updateItem(item, empty)
        val label = item?.let(::shortMoveDirectionLabel)
        text = if (empty) null else label
        styleClass.removeAll(
            "short-move-up", "short-move-down", "short-move-struggle",
            "rapid-crash-cell"
        )
        if (!empty && item != null && label != null) {
            if (item.pattern == ShortMovePattern.RAPID_CRASH) {
                styleClass += "rapid-crash-cell"
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
