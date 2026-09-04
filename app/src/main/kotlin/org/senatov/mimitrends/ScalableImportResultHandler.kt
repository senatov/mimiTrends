package org.senatov.mimitrends

import javafx.scene.control.Button
import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger

internal class ScalableImportResultHandler(
    private val button: Button,
    private val setStatus: (String, Boolean, String?) -> Unit,
    private val formatError: (String, Throwable) -> String,
    private val log: Logger,
    private val onCompleted: () -> Unit = {}
) {
    fun handle(event: ScalableImportEvent) {
        when (event) {
            is ScalableImportEvent.Started -> {
                button.isDisable = true
                setStatus("Importing Scalable transactions from ${event.fileName}", false, null)
            }
            is ScalableImportEvent.Completed -> {
                button.isDisable = false
                val result = event.result
                val reconciliation = "${result.closedPositions} closed · ${result.openPositions} open" +
                    if (result.correctedOrder == 0) "" else " · ${result.correctedOrder} order corrected" +
                    if (result.unmatchedSells == 0) "" else " · ${result.unmatchedSells} unmatched sells"
                setStatus("Scalable import: ${result.imported} new · ${result.duplicates} duplicates skipped · " +
                    "${result.linkedToSignals} linked to saved signals · $reconciliation",
                    result.unmatchedSells > 0, null)
                onCompleted()
            }
            is ScalableImportEvent.Failed -> {
                button.isDisable = false
                log.warn(LogTag.DB, "Scalable CSV import failed path={}", event.path, event.error)
                setStatus("Scalable import failed: ${event.error.message}", true,
                    formatError("Import ${event.path}", event.error))
            }
        }
    }
}
