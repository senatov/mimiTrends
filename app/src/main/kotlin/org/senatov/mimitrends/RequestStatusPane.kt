package org.senatov.mimitrends

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZonedDateTime

internal class RequestStatusPane(private val selectedRange: () -> String) : HBox(8.0) {
    private val statusLabel = Label()
    private val detailsButton = Button("!")
    private var lastDetails: String? = null

    init {
        alignment = Pos.CENTER_LEFT
        styleClass += "request-status-bar"
        detailsButton.styleClass += "error-details-button"
        detailsButton.tooltip = Tooltip("Show complete error log")
        detailsButton.isVisible = false
        detailsButton.isManaged = false
        detailsButton.setOnAction { showErrorDetails() }
        children += listOf(statusLabel, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, detailsButton)
    }

    fun update(message: String, error: Boolean = false, details: String? = null) {
        statusLabel.text = message
        statusLabel.styleClass.removeAll("status-error")
        if (error) statusLabel.styleClass += "status-error"
        lastDetails = details
        detailsButton.isVisible = error && !details.isNullOrBlank()
        detailsButton.isManaged = detailsButton.isVisible
    }

    fun formatError(query: String, error: Throwable?, message: String? = null): String {
        val stackTrace = if (error == null) message ?: "No exception stack trace is available."
        else StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        return buildString {
            appendLine("MiMiTrends error report")
            appendLine("Time: ${ZonedDateTime.now()}")
            appendLine("Query: $query")
            appendLine("Range: ${selectedRange()}")
            appendLine()
            append(stackTrace)
        }
    }

    private fun showErrorDetails() {
        val details = lastDetails ?: return
        Dialog<ButtonType>().apply {
            detailsButton.scene?.window?.let(::initOwner)
            title = "MiMiTrends error log"
            headerText = statusLabel.text
            dialogPane.content = TextArea(details).apply {
                isEditable = false; isWrapText = false; prefColumnCount = 100; prefRowCount = 28
                styleClass += "error-log-area"
            }
            dialogPane.buttonTypes += ButtonType.CLOSE
            isResizable = true
        }.showAndWait()
    }
}
