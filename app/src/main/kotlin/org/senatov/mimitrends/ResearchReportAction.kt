package org.senatov.mimitrends

import javafx.application.Platform
import javafx.scene.control.Button
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.WalkForwardResearchReport
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor

internal class ResearchReportAction(
    private val analytics: AnalyticsRepository,
    private val executor: Executor,
    private val setStatus: (String) -> Unit
) {
    val button = Button("∿")
    private val log = LoggerFactory.getLogger(javaClass)

    fun configure() {
        ToolbarIconButton.configure(button, "Prediction research and CSV export")
        button.setOnAction { load() }
    }

    private fun load() {
        button.isDisable = true
        setStatus("Building walk-forward research report")
        executor.execute {
            runCatching { listOf(5, 10, 30).map(analytics::walkForwardResearchReport) }
                .onSuccess { reports -> Platform.runLater { show(reports) } }
                .onFailure { error ->
                    log.warn(LogTag.DB, "walk-forward research report failed", error)
                    Platform.runLater {
                        button.isDisable = false
                        setStatus("Research report failed: ${error.message ?: "unknown error"}")
                    }
                }
        }
    }

    private fun show(reports: List<WalkForwardResearchReport>) {
        button.isDisable = false
        setStatus("Research report ready · ${reports.sumOf(WalkForwardResearchReport::evaluatedSamples)} evaluated outcomes")
        if (!ResearchReportDialog.show(button.scene?.window, reports)) return
        val path = ResearchReportExport.choose(button.scene?.window) ?: return
        executor.execute {
            runCatching { ResearchReportExport.write(path, reports) }
                .onSuccess { Platform.runLater { setStatus("Exported prediction research: ${path.fileName}") } }
                .onFailure { error ->
                    log.warn(LogTag.IO, "research report export failed path={}", path, error)
                    Platform.runLater { setStatus("Research export failed: ${error.message ?: "unknown error"}") }
                }
        }
    }
}
