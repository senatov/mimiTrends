package org.senatov.mimitrends

import javafx.application.Platform
import javafx.stage.FileChooser
import javafx.stage.Window
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.db.BrokerImportResult
import java.nio.file.Path
import java.util.concurrent.Executor

class ScalableImportAction(
    private val analytics: AnalyticsRepository,
    private val executor: Executor,
    private val homeDirectory: Path = Path.of(System.getProperty("user.home"))
) {
    fun chooseAndImport(owner: Window?, onEvent: (ScalableImportEvent) -> Unit) {
        val file = chooser().showOpenDialog(owner) ?: return
        onEvent(ScalableImportEvent.Started(file.name))
        executor.execute {
            runCatching { analytics.importScalableTransactions(file.toPath()) }
                .onSuccess { result -> Platform.runLater { onEvent(ScalableImportEvent.Completed(result)) } }
                .onFailure { error -> Platform.runLater { onEvent(ScalableImportEvent.Failed(file.toPath(), error)) } }
        }
    }

    private fun chooser() = FileChooser().apply {
        title = "Import Scalable transactions"
        extensionFilters += FileChooser.ExtensionFilter("Scalable CSV files", "*.csv")
        homeDirectory.resolve("Downloads").toFile().takeIf { it.isDirectory }?.let { initialDirectory = it }
    }
}

sealed interface ScalableImportEvent {
    data class Started(val fileName: String) : ScalableImportEvent
    data class Completed(val result: BrokerImportResult) : ScalableImportEvent
    data class Failed(val path: Path, val error: Throwable) : ScalableImportEvent
}
