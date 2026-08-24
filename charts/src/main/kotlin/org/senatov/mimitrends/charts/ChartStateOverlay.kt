package org.senatov.mimitrends.charts

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.VBox

internal class ChartStateOverlay : VBox(9.0) {
    private val progress = ProgressIndicator().apply { maxWidth = 32.0; maxHeight = 32.0 }
    private val title = Label("Loading chart…").apply { styleClass += "chart-state-title" }
    private val detail = Label().apply {
        styleClass += "chart-state-detail"
        isWrapText = true
        maxWidth = 420.0
    }
    private val action = Button().apply { styleClass += "chart-state-action" }

    init {
        alignment = Pos.CENTER
        styleClass += "chart-state-overlay"
        children += listOf(progress, title, detail, action)
        hide()
    }

    fun showLoading(loading: Boolean) {
        progress.isVisible = loading
        title.text = "Loading chart…"
        detail.text = "Reading collected bars and broker activity"
        styleClass.removeAll("chart-state-empty", "chart-state-error")
        action.isVisible = false
        action.isManaged = false
        isVisible = loading
        isManaged = loading
    }

    fun showMessage(heading: String, description: String, stateStyle: String) {
        progress.isVisible = false
        title.text = heading
        detail.text = description
        styleClass.removeAll("chart-state-empty", "chart-state-error")
        styleClass += stateStyle
        isVisible = true
        isManaged = true
    }

    fun showAction(caption: String, handler: () -> Unit) {
        action.text = caption
        action.accessibleText = caption
        action.setOnAction { handler() }
        action.isVisible = true
        action.isManaged = true
    }

    private fun hide() {
        isVisible = false
        isManaged = false
        action.isVisible = false
        action.isManaged = false
    }
}
