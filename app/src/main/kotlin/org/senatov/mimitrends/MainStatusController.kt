package org.senatov.mimitrends

import javafx.scene.control.Button
import org.senatov.mimitrends.charts.TrendChartView
import org.senatov.mimitrends.log.LogTag
import org.slf4j.Logger

internal class MainStatusController(
    private val pane: RequestStatusPane,
    private val chart: TrendChartView,
    private val refreshButton: Button,
    private val log: Logger
) {
    fun update(message: String) = update(message, false, null)

    fun update(message: String, error: Boolean, details: String?) {
        log.debug(LogTag.UI, "status update message={} error={} details={}", message, error, details != null)
        pane.update(message, error, details)
    }

    fun setLoading(value: Boolean) {
        log.debug(LogTag.UI, "chart loading value={}", value)
        chart.setLoading(value)
        refreshButton.isDisable = value
    }
}
