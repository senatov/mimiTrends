package org.senatov.mimitrends

import javafx.scene.control.CheckMenuItem
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView

internal class TableColumnLayout<T>(
    private val table: TableView<T>,
    saved: String
) {
    private val savedEntries = parse(saved)
    private val defaultColumns = table.columns.toList()
    private val defaultWidths = defaultColumns.associateWith { it.prefWidth }

    fun install() {
        val known = table.columns.associateBy { it.id }
        val ordered = savedEntries.mapNotNull { known[it.id] } + table.columns.filter { it.id !in savedEntries.map(Entry::id) }
        table.columns.setAll(ordered)
        savedEntries.forEach { entry ->
            known[entry.id]?.apply {
                isVisible = entry.visible
                if (entry.width.isFinite() && entry.width >= minWidth) prefWidth = entry.width
            }
        }
        val menu = ContextMenu().apply { items += visibilityItems(known.values) }
        known.values.forEach { it.contextMenu = menu }
    }

    fun menuButton(onReset: () -> Unit = {}): MenuButton = MenuButton("Columns").apply {
        styleClass += "table-columns-button"
        items += visibilityItems(table.columns)
        items += SeparatorMenuItem()
        items += MenuItem("Restore default columns").apply {
            setOnAction {
                table.columns.setAll(defaultColumns)
                defaultColumns.forEach { column ->
                    column.isVisible = true
                    column.prefWidth = defaultWidths.getValue(column)
                }
                onReset()
            }
        }
    }

    private fun visibilityItems(columns: Collection<TableColumn<T, *>>): List<CheckMenuItem> =
        columns.map { column ->
            CheckMenuItem(column.text).apply {
                isSelected = column.isVisible
                selectedProperty().addListener { _, _, visible ->
                    if (!visible && table.columns.count(TableColumn<T, *>::isVisible) == 1) {
                        isSelected = true
                    } else {
                        column.isVisible = visible
                    }
                }
                column.visibleProperty().addListener { _, _, visible -> isSelected = visible }
            }
        }

    fun savedWidths(): Map<String, Double> = savedEntries.associate { it.id to it.width }

    fun manuallySizedColumnIds(): Set<String> = savedEntries.filter(Entry::manual).mapTo(mutableSetOf(), Entry::id)

    fun capture(manuallySizedIds: Set<String> = emptySet()): String = table.columns.joinToString(";") { column ->
        "${column.id},${column.isVisible},${"%.1f".format(java.util.Locale.ROOT, column.width)}," +
            "${column.id in manuallySizedIds}"
    }

    private fun parse(value: String): List<Entry> = value.split(';').mapNotNull { encoded ->
        val parts = encoded.split(',')
        val width = parts.getOrNull(2)?.toDoubleOrNull() ?: return@mapNotNull null
        parts.firstOrNull()?.takeIf(String::isNotBlank)?.let {
            Entry(it, parts.getOrNull(1).toBoolean(), width, parts.getOrNull(3).toBoolean())
        }
    }

    private data class Entry(val id: String, val visible: Boolean, val width: Double, val manual: Boolean)
}
