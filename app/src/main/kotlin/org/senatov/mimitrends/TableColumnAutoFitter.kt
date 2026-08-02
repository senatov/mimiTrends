package org.senatov.mimitrends

import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.util.Duration
import kotlin.math.ceil

class TableColumnAutoFitter<T>(
    private val table: TableView<T>,
    private val specs: List<Spec<T>>
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

    init {
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

    private fun fitNow() {
        if (table.width <= 0.0 || table.items.isEmpty()) return
        table.applyCss()
        val font = table.lookupAll(".table-cell").firstNotNullOfOrNull { (it as? TableCell<*, *>)?.font }
            ?: Font.getDefault()
        val sampled = sample(table.items)
        val measured = specs.associateWith { measure(it, sampled, font) }.toMutableMap()
        val flexible = specs.firstOrNull(Spec<T>::flexible)
        if (flexible != null) {
            val fixedWidth = specs.filterNot(Spec<T>::flexible).sumOf { measured.getValue(it) }
            val remainder = (table.width - fixedWidth - TRAILING_INSET - specs.size * DIVIDER_RESERVE).coerceAtLeast(flexible.minWidth)
            val measuredFlexible = measured.getValue(flexible).coerceAtMost(table.width * FLEXIBLE_MAX_FRACTION)
            measured[flexible] = maxOf(measuredFlexible, remainder).coerceAtMost(flexible.maxWidth)
        }
        specs.forEach { spec ->
            val width = measured.getValue(spec)
            if (kotlin.math.abs(spec.column.width - width) >= WIDTH_STABILITY_EPSILON) spec.column.prefWidth = width
        }
    }

    private fun measure(spec: Spec<T>, rows: List<T>, font: Font): Double {
        val widths = rows.asSequence().map(spec.text).filter(String::isNotBlank).map { textWidth(it, font) }.toList()
        val contentWidth = percentile85(trimmed(widths))
        val headerWidth = textWidth(spec.column.text.orEmpty(), font) + HEADER_RESERVE
        return ceil(maxOf(contentWidth + CONTENT_INSETS + spec.reserveWidth, headerWidth))
            .coerceIn(spec.minWidth, spec.maxWidth)
    }

    private fun textWidth(value: String, font: Font): Double = Text(value).apply { this.font = font }.layoutBounds.width

    private fun trimmed(values: List<Double>): List<Double> {
        if (values.size < 5) return values
        val sorted = values.sorted()
        val trim = maxOf(1, (sorted.size * 0.10).toInt())
        return sorted.subList(trim, sorted.size - trim).takeIf(List<Double>::isNotEmpty) ?: values
    }

    private fun percentile85(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = (ceil(sorted.size * 0.85).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

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
        const val CONTENT_INSETS = 18.0
        const val HEADER_RESERVE = 28.0
        const val TRAILING_INSET = 18.0
        const val DIVIDER_RESERVE = 1.0
        const val FLEXIBLE_MAX_FRACTION = 0.45
        const val WIDTH_STABILITY_EPSILON = 1.0
    }
}
