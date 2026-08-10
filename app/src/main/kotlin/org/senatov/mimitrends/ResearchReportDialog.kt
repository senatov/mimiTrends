package org.senatov.mimitrends

import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.geometry.Insets
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Window
import org.senatov.mimitrends.db.WalkForwardMetric
import org.senatov.mimitrends.db.WalkForwardResearchReport
import java.util.Locale

internal object ResearchReportDialog {
    private val exportButton = ButtonType("Export CSV…", ButtonBar.ButtonData.APPLY)

    fun show(owner: Window?, reports: List<WalkForwardResearchReport>): Boolean {
        val dialog = Dialog<ButtonType>().apply {
            owner?.let(::initOwner)
            title = "Prediction research"
            dialogPane.headerText = "Walk-forward signal evaluation"
            dialogPane.buttonTypes.setAll(exportButton, ButtonType.CLOSE)
            dialogPane.styleClass += "glass-settings-dialog"
            dialogPane.content = content(reports)
            isResizable = true
        }
        WindowGeometryService("research-report", 900.0, 600.0).attach(dialog)
        return dialog.showAndWait().orElse(ButtonType.CLOSE) == exportButton
    }

    private fun content(reports: List<WalkForwardResearchReport>) = VBox(10.0,
        Label("Every row is evaluated using outcomes from earlier trading days only. " +
            "Returns include the configured 0.20% friction allowance.").apply {
            isWrapText = true
            styleClass += "settings-help"
        },
        TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs.setAll(reports.sortedBy(WalkForwardResearchReport::horizonMinutes).map(::tab))
            VBox.setVgrow(this, Priority.ALWAYS)
        }
    ).apply { padding = Insets(4.0) }

    private fun tab(report: WalkForwardResearchReport) = Tab("${report.horizonMinutes} min").apply {
        content = if (report.metrics.isEmpty()) emptyState(report) else reportTable(report)
    }

    private fun emptyState(report: WalkForwardResearchReport) = VBox(8.0,
        Label("Not enough historical days yet"),
        Label("Collected outcomes: ${report.outcomeSamples}. A family needs at least 20 earlier samples " +
            "before a later day can be evaluated.").apply { isWrapText = true }
    ).apply { padding = Insets(24.0) }

    private fun reportTable(report: WalkForwardResearchReport): VBox {
        val table = TableView<WalkForwardMetric>().apply {
            items.setAll(report.metrics)
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            columns.setAll(
                textColumn("Signal family") { it.family },
                textColumn("Direction") { if (it.direction < 0) "Down" else "Up" },
                numberColumn("Samples") { it.samples.toString() },
                numberColumn("Days") { it.distinctDays.toString() },
                numberColumn("Predicted") { percent(it.predictedWinRate) },
                numberColumn("Actual") { percent(it.actualWinRate) },
                numberColumn("Brier") { decimal(it.brierScore) },
                numberColumn("Avg net") { signedPercent(it.averageNetReturnPercent) }
            )
        }
        return VBox(8.0,
            Label("Outcomes: ${report.outcomeSamples} · walk-forward evaluated: ${report.evaluatedSamples}"),
            table
        ).apply {
            padding = Insets(8.0, 0.0, 0.0, 0.0)
            VBox.setVgrow(table, Priority.ALWAYS)
        }
    }

    private fun textColumn(title: String, value: (WalkForwardMetric) -> String) =
        TableColumn<WalkForwardMetric, String>(title).apply {
            setCellValueFactory { ReadOnlyObjectWrapper(value(it.value)) }
        }

    private fun numberColumn(title: String, value: (WalkForwardMetric) -> String) =
        textColumn(title, value).apply { style = "-fx-alignment: CENTER-RIGHT;" }

    private fun percent(value: Double) = String.format(Locale.ROOT, "%.1f%%", value * 100.0)
    private fun signedPercent(value: Double) = String.format(Locale.ROOT, "%+.3f%%", value)
    private fun decimal(value: Double) = String.format(Locale.ROOT, "%.3f", value)
}
