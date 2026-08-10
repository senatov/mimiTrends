package org.senatov.mimitrends

import javafx.application.Platform
import javafx.stage.Window
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
    private val log = LoggerFactory.getLogger(javaClass)
    private val backfill = ResearchBackfillService(marketRepository, analytics, scanner)
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "mimitrends-research").apply { isDaemon = true }
    }

    fun start() {
        executor.scheduleWithFixedDelay(::automaticMaintenance, 30, 6 * 60 * 60, TimeUnit.SECONDS)
    }

    fun show(owner: Window?) = load(owner)

    private fun automaticMaintenance() {
        runCatching {
            if (analytics.needsResearchBackfill()) {
                val result = backfill.run(criteria()) { _, _, _ -> }
                log.info(LogTag.DB, "automatic research backfill completed symbols={} samples={}",
                    result.symbols, result.samples)
            }
            analytics.trainPredictiveModels()
        }.onSuccess { results ->
            results.forEach { result -> log.info(LogTag.DB,
                "predictive training horizon={}m status={} training={} validation={} modelBrier={} baselineBrier={} reason={}",
                result.horizonMinutes, result.status, result.trainingSamples, result.validationSamples,
                result.modelBrier, result.baselineBrier, result.reason) }
        }.onFailure { error -> log.warn(LogTag.DB, "automatic predictive maintenance failed", error) }
    }

    private fun load(owner: Window?) {
        setStatus("Building walk-forward research report")
        executor.execute {
            runCatching { listOf(5, 10, 30).map(analytics::walkForwardResearchReport) }
                .onSuccess { reports -> Platform.runLater { show(owner, reports) } }
                .onFailure { error ->
                    log.warn(LogTag.DB, "walk-forward research report failed", error)
                    Platform.runLater {
                        setStatus("Research report failed: ${error.message ?: "unknown error"}")
                    }
                }
        }
    }

    private fun show(owner: Window?, reports: List<WalkForwardResearchReport>) {
        setStatus("Research report ready · ${reports.sumOf(WalkForwardResearchReport::evaluatedSamples)} evaluated outcomes")
        when (ResearchReportDialog.show(owner, reports)) {
            ResearchReportChoice.BACKFILL -> runBackfill(owner)
            ResearchReportChoice.EXPORT -> export(owner, reports)
            ResearchReportChoice.CLOSE -> Unit
        }
    }

    private fun export(owner: Window?, reports: List<WalkForwardResearchReport>) {
        val path = ResearchReportExport.choose(owner) ?: return
        executor.execute {
            runCatching { ResearchReportExport.write(path, reports) }
                .onSuccess { Platform.runLater { setStatus("Exported prediction research: ${path.fileName}") } }
                .onFailure { error ->
                    log.warn(LogTag.IO, "research report export failed path={}", path, error)
                    Platform.runLater { setStatus("Research export failed: ${error.message ?: "unknown error"}") }
                }
        }
    }

    private fun runBackfill(owner: Window?) {
        setStatus("Backfilling point-in-time research history")
        executor.execute {
            runCatching {
                val backfillResult = backfill.run(criteria()) { completed, total, symbol ->
                Platform.runLater { setStatus("Research backfill: $completed/$total · $symbol") }
                }
                backfillResult to analytics.trainPredictiveModels()
            }.onSuccess { (result, training) ->
                Platform.runLater {
                    setStatus("Research backfill complete · ${result.samples} samples · " +
                        "${training.count { it.status == "ACTIVE" }} active models")
                    load(owner)
                }
            }.onFailure { error ->
                log.warn(LogTag.DB, "research backfill failed", error)
                Platform.runLater {
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
