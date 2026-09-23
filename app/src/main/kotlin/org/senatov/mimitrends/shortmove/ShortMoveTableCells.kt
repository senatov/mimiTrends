package org.senatov.mimitrends.shortmove

import javafx.scene.control.TableCell
import javafx.scene.control.Tooltip

internal fun shortMoveAlertPriority(move: ShortMove): Int = when (move.pattern) {
    ShortMovePattern.RAPID_CRASH -> 0
    ShortMovePattern.TRADABLE_CORRIDOR -> 1
}

internal fun shortMoveDirectionLabel(move: ShortMove): String = when (move.pattern) {
    ShortMovePattern.RAPID_CRASH -> "‼ RAPID CRASH"
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
        if (!empty && item != null && label != null) styleClass += when {
            item.pattern == ShortMovePattern.RAPID_CRASH -> "rapid-crash-cell"
            else -> "short-move-down"
        }
    }
}

internal class ShortMovePercentCell : TableCell<ShortMove, Number>() {
    override fun updateItem(item: Number?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty || item == null) null else "%+.2f%%".format(item.toDouble())
        styleClass.removeAll("short-move-up", "short-move-down")
        if (!empty && item != null) styleClass += if (item.toDouble() >= 0.0) "short-move-up" else "short-move-down"
    }
}

internal class ShortMoveOpportunityCell : TableCell<ShortMove, Number>() {
    override fun updateItem(item: Number?, empty: Boolean) {
        super.updateItem(item, empty)
        styleClass.removeAll(
            "opportunity-high", "opportunity-good", "opportunity-wait",
            "opportunity-late", "opportunity-avoid"
        )
        if (empty || item == null || item.toInt() < 0) {
            text = null
            tooltip = null
            return
        }
        val value = item.toInt().coerceIn(0, 100)
        text = "$value%"
        styleClass += when {
            value >= 80 -> "opportunity-high"
            value >= 60 -> "opportunity-good"
            value >= 40 -> "opportunity-wait"
            value >= 20 -> "opportunity-late"
            else -> "opportunity-avoid"
        }
        tooltip = tableRow?.item?.let { move ->
            val retentionNote = if (move.isRetained) {
                "Recently detected; no longer confirmed by the latest scan."
            } else null
            Tooltip(listOfNotNull(retentionNote, move.opportunityDetails.takeIf(String::isNotBlank)).joinToString("\n"))
        }
    }
}

internal class ShortMovePriceRangeCell : TableCell<ShortMove, ShortMove>() {
    override fun updateItem(item: ShortMove?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty || item == null) null else ShortMovePricePresentation.text(item)
        styleClass.removeAll("short-move-up", "short-move-down")
        if (!empty && item != null) {
            styleClass += if (item.changePercent >= 0.0) "short-move-up" else "short-move-down"
        }
    }
}
