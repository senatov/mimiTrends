package org.senatov.mimitrends.ui

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

    fun success(message: String) = pane.update(message, state = StatusState.SUCCESS)

    fun warning(message: String) = pane.update(message, state = StatusState.WARNING)

    fun transientSuccess(message: String) = pane.showTransient(message)

    fun setLoading(value: Boolean) {
        log.debug(LogTag.UI, "chart loading value={}", value)
        chart.setLoading(value)
        refreshButton.isDisable = value
        pane.setLoading(value)
    }
}
