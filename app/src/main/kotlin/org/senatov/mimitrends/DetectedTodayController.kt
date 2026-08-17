package org.senatov.mimitrends

import javafx.application.Platform
import org.senatov.mimitrends.db.AnalyticsRepository
import java.util.concurrent.Executor

internal class DetectedTodayController(
    private val analytics: AnalyticsRepository,
    private val executor: Executor,
    private val panel: ScannerPanel
) {
    fun show() {
        executor.execute {
            val detections = analytics.loadTodayDetections()
            Platform.runLater {
                panel.setDetectedTodayCount(detections.size)
                DetectedTodayDialog.show(panel.scene?.window, detections)
            }
        }
    }

    fun refreshCount() {
        executor.execute {
            val count = analytics.loadTodayDetections().size
            Platform.runLater { panel.setDetectedTodayCount(count) }
        }
    }
}
