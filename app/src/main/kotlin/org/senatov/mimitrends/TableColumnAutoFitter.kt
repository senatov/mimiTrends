package org.senatov.mimitrends

import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.Labeled
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.util.Duration
import kotlin.math.ceil

class TableColumnAutoFitter<T>(
    private val table: TableView<T>,
    private val specs: List<Spec<T>>,
    preservedWidths: Map<String, Double> = emptyMap(),
    preservedManualIds: Set<String> = emptySet()
) {
    data class Spec<T>(
        val column: TableColumn<T, *>,
        val text: (T) -> String,
        val minWidth: Double,
        val maxWidth: Double,
        val flexible: Boolean = false,
        val reserveWidth: Double = 0.0
    )

    private val debounce = PauseTransition(Duration.millis(140.0)).apply { setOnFinished { fitNow() } }
    private val manuallySized = mutableSetOf<TableColumn<T, *>>()
    private var widthsBeforePointerAction = emptyMap<TableColumn<T, *>, Double>()
    private var applying = false

    init {
        specs.forEach { spec ->
            preservedWidths[spec.column.id]?.let { width ->
                spec.column.prefWidth = width.coerceIn(spec.column.minWidth, spec.maxWidth)
            }
            if (spec.column.id in preservedManualIds) manuallySized += spec.column
        }
        table.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (event.button == MouseButton.PRIMARY) widthsBeforePointerAction = specs.associate { it.column to it.column.width }
        }
        table.addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (event.button == MouseButton.PRIMARY) rememberManualResize()
        }
        table.widthProperty().addListener { _, old, new ->
            if (kotlin.math.abs(new.toDouble() - old.toDouble()) >= WIDTH_STABILITY_EPSILON) request()
        }
    }

    fun request() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(::request)
            return
        }
        debounce.playFromStart()
    }

    fun manuallySizedColumnIds(): Set<String> = manuallySized.mapTo(mutableSetOf()) { it.id }

    fun resetManualSizing() {
        manuallySized.clear()
        request()
    }

    private fun rememberManualResize() {
        if (applying) return
        widthsBeforePointerAction.forEach { (column, width) ->
            if (kotlin.math.abs(column.width - width) >= WIDTH_STABILITY_EPSILON) manuallySized += column
        }
        widthsBeforePointerAction = emptyMap()
    }

    private fun fitNow() {
        if (table.width <= 0.0 || table.items.isEmpty()) return
        table.applyCss()
        val cellFont = table.lookupAll(".table-cell").firstNotNullOfOrNull { (it as? TableCell<*, *>)?.font }
            ?: Font.getDefault()
        val headerFont = table.lookupAll(".column-header .label").firstNotNullOfOrNull { (it as? Labeled)?.font }
            ?: cellFont
        val sampled = sample(table.items)
        applying = true
        try {
            specs.filterNot { it.column in manuallySized }.forEach { spec ->
                val width = measure(spec, sampled, cellFont, headerFont)
                if (kotlin.math.abs(spec.column.width - width) >= WIDTH_STABILITY_EPSILON) spec.column.prefWidth = width
            }
        } finally {
            applying = false
        }
    }

    private fun measure(spec: Spec<T>, rows: List<T>, cellFont: Font, headerFont: Font): Double {
        val contentWidth = rows.asSequence().map(spec.text).filter(String::isNotBlank)
            .maxOfOrNull { textWidth(it, cellFont) } ?: 0.0
        val headerWidth = textWidth(spec.column.text.orEmpty(), headerFont) + HEADER_RESERVE
        return ceil(maxOf(contentWidth + CONTENT_INSETS + spec.reserveWidth, headerWidth))
            .coerceIn(spec.minWidth, spec.maxWidth)
    }

    private fun textWidth(value: String, font: Font): Double = Text(value).apply { this.font = font }.layoutBounds.width

    private fun sample(rows: List<T>): List<T> {
        if (rows.size <= SAMPLE_LIMIT) return rows
        val result = rows.take(SAMPLE_HEAD).toMutableList()
        val tail = rows.drop(SAMPLE_HEAD)
        val stride = maxOf(1, tail.size / (SAMPLE_LIMIT - SAMPLE_HEAD))
        tail.forEachIndexed { index, row -> if (index % stride == 0 && result.size < SAMPLE_LIMIT) result += row }
        return result
    }

    private companion object {
        const val SAMPLE_LIMIT = 500
        const val SAMPLE_HEAD = 200
        const val CONTENT_INSETS = 12.0
        const val HEADER_RESERVE = 22.0
        const val WIDTH_STABILITY_EPSILON = 1.0
    }
}
