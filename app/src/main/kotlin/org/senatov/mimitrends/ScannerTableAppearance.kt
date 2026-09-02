package org.senatov.mimitrends

import javafx.scene.control.TableView
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.TableAppearance
import org.senatov.mimitrends.model.UiTheme

internal object ScannerTableAppearance {
    fun apply(table: TableView<ScanResult>, value: TableAppearance) {
        val safeFont = value.fontFamily.replace("\"", "")
        val colors = if (value.theme == UiTheme.DARK) {
            listOf("#D7DEE7", "#19212B", "#202A35", "#254A70", "#354250")
        } else {
            listOf(value.textColor, value.evenRowColor, value.oddRowColor, value.selectionColor, value.gridColor)
        }
        table.style = """
            -fx-font-family: "$safeFont";
            -fx-font-size: ${value.fontSize}px;
            -mimi-table-text: ${colors[0]};
            -mimi-row-even: ${colors[1]};
            -mimi-row-odd: ${colors[2]};
            -mimi-selection: ${colors[3]};
            -mimi-table-grid: ${colors[4]};
        """.trimIndent()
        table.refresh()
    }
}
