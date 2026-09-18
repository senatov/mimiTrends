package org.senatov.mimitrends.shared

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

import javafx.application.Platform
import org.senatov.mimitrends.db.AnalyticsRepository
import org.senatov.mimitrends.model.FinancialTransactionTaxExclusions
import java.util.concurrent.Executor

internal class DetectedTodayController(
    private val analytics: AnalyticsRepository,
    private val executor: Executor,
    private val panel: ScannerPanel
) {
    fun show() {
        executor.execute {
            val detections = analytics.loadTodayDetections()
                .filterNot { FinancialTransactionTaxExclusions.contains(it.symbol) }
            Platform.runLater {
                panel.setDetectedTodayCount(detections.size)
                DetectedTodayDialog.show(panel.scene?.window, detections)
            }
        }
    }

    fun refreshCount() {
        executor.execute {
            val count = analytics.loadTodayDetections()
                .count { !FinancialTransactionTaxExclusions.contains(it.symbol) }
            Platform.runLater { panel.setDetectedTodayCount(count) }
        }
    }
}
