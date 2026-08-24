package org.senatov.mimitrends.charts

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.Tooltip
import javafx.scene.control.OverrunStyle
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

internal class TrendChartHeader(
    onViewChanged: () -> Unit,
    onTradesChanged: () -> Unit,
    onRangeChanged: (String) -> Unit
) : VBox(6.0) {
    private val instrument = Label("Select a scanner result")
    private val price = Label()
    private val context = Label("Price and volume history")
    private val signal = Label()
    private val cursor = Label(CURSOR_PROMPT)
    private val focus = ToggleButton("Signal focus")
    private val history = ToggleButton("Full history")
    private val trades = ToggleButton("Trades").apply { isSelected = true }
    private val rangeButtons = RANGE_LABELS.associateWith(::ToggleButton)

    val focused: Boolean get() = focus.isSelected
    val tradesVisible: Boolean get() = trades.isSelected
    var cursorText: String
        get() = cursor.text
        set(value) { cursor.text = value }

    init {
        val viewModes = ToggleGroup().apply {
            focus.toggleGroup = this
            history.toggleGroup = this
            selectToggle(focus)
        }
        configureButton(focus, "Show detailed candles around the selected signal") {
            if (viewModes.selectedToggle == null) focus.isSelected = true
            onViewChanged()
        }
        configureButton(history, "Show the complete loaded chart range") {
            if (viewModes.selectedToggle == null) history.isSelected = true
            onViewChanged()
        }
        trades.styleClass += listOf("chart-mode-button", "chart-overlay-button")
        trades.tooltip = Tooltip("Show or hide executed broker trades")
        trades.setOnAction { onTradesChanged() }
        val ranges = ToggleGroup()
        rangeButtons.forEach { (range, button) ->
            button.toggleGroup = ranges
            button.styleClass += listOf("chart-range-button", "chart-mode-button")
            button.setOnAction {
                if (ranges.selectedToggle == null) ranges.selectToggle(button)
                onRangeChanged(range)
            }
        }

        signal.styleClass += "chart-signal-summary"
        context.styleClass += "chart-context-details"
        configureFlexibleText(instrument)
        configureFlexibleText(signal)
        configureFlexibleText(context)
        cursor.styleClass += "chart-cursor-details"
        cursor.isWrapText = true
        cursor.minWidth = 0.0
        cursor.maxWidth = Double.MAX_VALUE
        price.styleClass += "chart-current-price"
        instrument.styleClass += "chart-instrument-title"

        val titleRow = HBox(10.0, instrument, price).apply {
            alignment = Pos.BASELINE_LEFT
            HBox.setHgrow(instrument, Priority.ALWAYS)
        }
        val metaRow = HBox(8.0, signal, context).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(context, Priority.ALWAYS)
        }
        val identity = VBox(2.0, titleRow, metaRow).apply {
            minWidth = 0.0
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val viewSwitch = HBox(focus, history).apply { styleClass += "chart-mode-switch" }
        val overlaySwitch = HBox(trades).apply { styleClass += listOf("chart-mode-switch", "chart-overlay-switch") }
        val rangeSwitch = HBox().apply {
            children += RANGE_LABELS.map(rangeButtons::getValue)
            styleClass += "chart-mode-switch"
        }
        val controls = HBox(8.0,
            controlGroup("VIEW", viewSwitch), controlGroup("OVERLAY", overlaySwitch)
        ).apply {
            alignment = Pos.BOTTOM_LEFT
            minWidth = Region.USE_PREF_SIZE
            styleClass += "chart-primary-controls"
        }
        val rangeRow = HBox(12.0, cursor, controlGroup("RANGE", rangeSwitch)).apply {
            alignment = Pos.BOTTOM_LEFT
            HBox.setHgrow(this@TrendChartHeader.cursor, Priority.ALWAYS)
            styleClass += "chart-range-row"
        }
        children += listOf(
            HBox(12.0, identity, controls).apply { alignment = Pos.CENTER_LEFT },
            rangeRow
        )
        styleClass += "chart-card-header"
    }

    fun selectFocus() {
        focus.isSelected = true
    }

    fun showInstrument(name: String, symbol: String, currentPrice: String, details: String, summary: String?) {
        val instrumentText = "$symbol  ·  $name"
        instrument.text = instrumentText
        instrument.tooltip = Tooltip(instrumentText)
        price.text = currentPrice
        context.text = details
        context.tooltip = Tooltip(details)
        signal.text = summary.orEmpty()
        signal.tooltip = summary?.let { Tooltip(it) }
        signal.isVisible = summary != null
        signal.isManaged = summary != null
    }

    fun selectRange(range: String) {
        rangeButtons[range]?.let { button -> button.toggleGroup.selectToggle(button) }
    }

    fun clear() {
        instrument.text = "No collected market data"
        price.text = ""
        context.text = ""
        signal.text = ""
        signal.isVisible = false
        signal.isManaged = false
        instrument.tooltip = null
        context.tooltip = null
        signal.tooltip = null
        cursor.text = CURSOR_PROMPT
    }

    private fun configureFlexibleText(label: Label) {
        label.minWidth = 0.0
        label.maxWidth = Double.MAX_VALUE
        label.textOverrun = OverrunStyle.ELLIPSIS
    }

    private fun configureButton(button: ToggleButton, help: String, action: () -> Unit) {
        button.styleClass += listOf("chart-mode-button", "chart-view-button")
        button.tooltip = Tooltip(help)
        button.setOnAction { action() }
    }

    private fun controlGroup(title: String, controls: HBox) = VBox(1.0,
        Label(title).apply { styleClass += "chart-mode-caption" }, controls
    )

    private companion object {
        const val CURSOR_PROMPT = "Move above a candle to inspect it · click to pin"
        val RANGE_LABELS = listOf("1D", "5D", "1M", "3M", "6M", "1Y")
    }
}
