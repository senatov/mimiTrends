package org.senatov.mimitrends.ui

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.Tooltip
import javafx.util.Duration

internal object TableColumnHelp {
    private const val TITLE_KEY = "mimitrends.column.title"

    fun install(column: TableColumn<*, *>, description: String) {
        val title = title(column)
        column.properties[TITLE_KEY] = title
        column.text = ""
        val graphic = Label(title).apply {
            styleClass += "table-column-title"
            accessibleText = "$title column"
            accessibleHelp = description
        }
        Tooltip.install(graphic, Tooltip(description).apply { showDelay = Duration.millis(400.0) })
        column.graphic = graphic
    }

    fun title(column: TableColumn<*, *>): String =
        column.properties[TITLE_KEY] as? String ?: column.text.orEmpty()
}
