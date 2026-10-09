package org.senatov.mimitrends.providers

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

internal class SourceActivityCard(
    source: String,
    onScalableLogin: () -> Unit
) : VBox(8.0) {
    private val name = Label(source).apply { styleClass += "source-card-name" }
    private val state = Label().apply { styleClass += "source-card-state" }
    private val lastContact = Label().apply { styleClass += "source-card-contact" }
    private val received = Label().apply { styleClass += "source-card-value" }
    private val valid = Label().apply { styleClass += "source-card-value" }
    private val operations = Label().apply { styleClass += "source-card-value" }
    private val failures = Label().apply { styleClass += "source-card-value" }
    private val statusTooltip = Tooltip()
    private val login = Button("Login").apply {
        styleClass += "source-login-button"
        tooltip = Tooltip("Open Scalable CLI login in Terminal")
        isVisible = false
        isManaged = false
        setOnAction { onScalableLogin() }
    }

    init {
        styleClass += "source-card"
        Tooltip.install(this, statusTooltip)
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val heading = HBox(7.0, Label("●").apply { styleClass += "source-card-led" }, name, spacer, state).apply {
            alignment = Pos.CENTER_LEFT
        }
        val metrics = HBox(
            12.0,
            metric("RECEIVED", received), metric("VALID", valid),
            metric("OPERATIONS", operations), metric("ERRORS", failures)
        ).apply { alignment = Pos.CENTER_LEFT }
        val footer = HBox(8.0, lastContact, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, login).apply {
            alignment = Pos.CENTER_LEFT
        }
        children.setAll(heading, metrics, footer)
    }

    fun update(snapshot: SourceActivitySnapshot, nowMillis: Long) {
        val health = SourceActivityPresentation.health(snapshot, nowMillis)
        styleClass.removeAll(SourceHealth.entries.map(SourceHealth::styleClass))
        styleClass += health.styleClass
        state.text = health.label
        received.text = snapshot.processed?.toString() ?: "—"
        valid.text = snapshot.accepted?.toString() ?: "—"
        operations.text = snapshot.operations.toString()
        failures.text = snapshot.failures.toString()
        lastContact.text = "LAST  ${SourceActivityPresentation.age(snapshot.lastContactMillis, nowMillis)}"
        lastContact.tooltip = Tooltip(
            "Last operation: ${SourceActivityPresentation.exactTime(snapshot.lastContactMillis)}\n" +
                "Last success: ${SourceActivityPresentation.exactTime(snapshot.lastSuccessMillis)}\n" +
                "Latest batch: ${SourceActivityPresentation.unit(snapshot.source)}"
        )
        val needsLogin = snapshot.source == "Scalable" && snapshot.status == "Login needed"
        login.isVisible = needsLogin
        login.isManaged = needsLogin
        statusTooltip.text = snapshot.status ?: "${snapshot.source} source telemetry"
    }

    private fun metric(title: String, value: Label): VBox = VBox(2.0,
        Label(title).apply { styleClass += "source-card-metric-label" }, value
    ).apply { alignment = Pos.CENTER_LEFT }
}
