package org.senatov.mimitrends

import javafx.application.Platform
import javafx.scene.control.Button
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.db.WalkForwardResearchReport
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.ScannerCriteria
import org.senatov.mimitrends.scanner.ScannerEngine
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class ResearchReportAction(
    private val analytics: AnalyticsRepository,
    marketRepository: MarketRepository,
    scanner: ScannerEngine,
    private val criteria: () -> ScannerCriteria,
    private val setStatus: (String) -> Unit
) : AutoCloseable {
    val button = Button("∿")
    private val log = LoggerFactory.getLogger(javaClass)
    private val backfill = ResearchBackfillService(marketRepository, analytics, scanner)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mimitrends-research").apply { isDaemon = true }
    }

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
        when (ResearchReportDialog.show(button.scene?.window, reports)) {
            ResearchReportChoice.BACKFILL -> runBackfill()
            ResearchReportChoice.EXPORT -> export(reports)
            ResearchReportChoice.CLOSE -> Unit
        }
    }

    private fun export(reports: List<WalkForwardResearchReport>) {
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

    private fun runBackfill() {
        button.isDisable = true
        setStatus("Backfilling point-in-time research history")
        executor.execute {
            runCatching { backfill.run(criteria()) { completed, total, symbol ->
                Platform.runLater { setStatus("Research backfill: $completed/$total · $symbol") }
            } }.onSuccess { result ->
                Platform.runLater {
                    button.isDisable = false
                    setStatus("Research backfill complete · ${result.samples} samples from ${result.symbols} symbols")
                    load()
                }
            }.onFailure { error ->
                log.warn(LogTag.DB, "research backfill failed", error)
                Platform.runLater {
                    button.isDisable = false
                    setStatus("Research backfill failed: ${error.message ?: "unknown error"}")
                }
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
