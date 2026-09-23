package org.senatov.mimitrends.ui

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.SplitPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.senatov.mimitrends.charts.TrendChartView

internal class ChartDrawer(
    chart: TrendChartView,
    initiallyExpanded: Boolean
) : VBox(4.0) {
    private val content = HBox(chart).apply {
        HBox.setHgrow(chart, Priority.ALWAYS)
        VBox.setVgrow(this, Priority.ALWAYS)
        minHeight = 0.0
    }
    private val toggle = Button()
    private var splitPane: SplitPane? = null
    private var expandedDivider = DEFAULT_EXPANDED_DIVIDER

    var isExpanded: Boolean = initiallyExpanded
        private set

    init {
        val header = HBox(
            8.0,
            Label("Selected alert chart").apply { styleClass += "chart-drawer-title" },
            Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
            toggle
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "chart-drawer-header"
        }
        toggle.styleClass += "chart-drawer-toggle"
        toggle.setOnAction { setExpanded(!isExpanded) }
        children.setAll(header, content)
        styleClass += "chart-drawer"
        setExpanded(initiallyExpanded, updateDivider = false)
    }

    fun attach(pane: SplitPane, divider: Double) {
        splitPane = pane
        expandedDivider = divider.coerceIn(MIN_EXPANDED_DIVIDER, MAX_EXPANDED_DIVIDER)
        Platform.runLater { updateDivider() }
    }

    fun show() = setExpanded(true)

    private fun setExpanded(expanded: Boolean, updateDivider: Boolean = true) {
        isExpanded = expanded
        content.isVisible = expanded
        content.isManaged = expanded
        toggle.text = if (expanded) "Hide chart" else "Show chart"
        toggle.accessibleText = toggle.text
        maxHeight = if (expanded) Double.MAX_VALUE else Region.USE_PREF_SIZE
        prefHeight = if (expanded) Region.USE_COMPUTED_SIZE else COLLAPSED_HEIGHT
        if (updateDivider) Platform.runLater(::updateDivider)
    }

    private fun updateDivider() {
        splitPane?.setDividerPosition(0, if (isExpanded) expandedDivider else COLLAPSED_DIVIDER)
    }

    private companion object {
        const val COLLAPSED_HEIGHT = 34.0
        const val COLLAPSED_DIVIDER = 0.94
        const val DEFAULT_EXPANDED_DIVIDER = 0.52
        const val MIN_EXPANDED_DIVIDER = 0.35
        const val MAX_EXPANDED_DIVIDER = 0.72
    }
}
