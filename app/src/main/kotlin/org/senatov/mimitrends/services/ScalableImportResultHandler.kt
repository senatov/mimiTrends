package org.senatov.mimitrends.services

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import org.senatov.mimitrends.db.BrokerImportResult
import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger

internal class ScalableImportResultHandler(
    private val button: Button,
    private val importStatus: Label,
    private val setStatus: (String, Boolean, String?) -> Unit,
    private val formatError: (String, Throwable) -> String,
    private val log: Logger,
    private val onCompleted: (BrokerImportResult) -> Unit = {}
) {
    fun handle(event: ScalableImportEvent) {
        when (event) {
            is ScalableImportEvent.Started -> {
                button.isDisable = true
                importStatus.text = "Trades: importing…"
                setStatus("Importing Scalable transactions from ${event.fileName}", false, null)
            }

            is ScalableImportEvent.Completed -> {
                button.isDisable = false
                val result = event.result
                val summary = "${result.imported} imported · ${result.rejected} rejected · " +
                        "${result.duplicates} duplicates · ${result.correctedOrder} corrected"
                importStatus.text = "Trades: $summary"
                importStatus.tooltip = Tooltip(
                    "$summary · ${result.closedPositions} closed · ${result.openPositions} open · " +
                            "${result.linkedToSignals} linked to saved signals"
                )
                val reconciliation = "${result.closedPositions} closed · ${result.openPositions} open" +
                        " · ${result.correctedOrder} order corrected" +
                                if (result.unmatchedSells == 0) "" else " · ${result.unmatchedSells} unmatched sells"
                setStatus(
                    "Scalable import: ${result.imported} imported · ${result.rejected} rejected · " +
                            "${result.duplicates} duplicates skipped · " +
                            "${result.linkedToSignals} linked to saved signals · $reconciliation",
                    result.unmatchedSells > 0, null
                )
                onCompleted(result)
            }

            is ScalableImportEvent.Failed -> {
                button.isDisable = false
                importStatus.text = "Trades: import failed"
                log.warn(LogTag.DB, "Scalable CSV import failed path={}", event.path, event.error)
                setStatus(
                    "Scalable import failed: ${event.error.message}", true,
                    formatError("Import ${event.path}", event.error)
                )
            }
        }
    }
}
