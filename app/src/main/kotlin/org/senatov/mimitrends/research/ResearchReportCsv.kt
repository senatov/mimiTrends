package org.senatov.mimitrends.research

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

import org.senatov.mimitrends.db.WalkForwardResearchReport
import java.util.Locale

internal object ResearchReportCsv {
    private const val HEADER = "horizon_minutes,friction_percent,family,direction,samples,distinct_days," +
            "predicted_win_rate,actual_win_rate,brier_score,average_net_return_percent"

    fun format(reports: Collection<WalkForwardResearchReport>): String = buildString {
        appendLine(HEADER)
        reports.sortedBy(WalkForwardResearchReport::horizonMinutes).forEach { report ->
            report.metrics.forEach { metric ->
                append(report.horizonMinutes).append(',')
                append(decimal(report.frictionPercent)).append(',')
                append(csv(metric.family)).append(',')
                append(if (metric.direction < 0) "down" else "up").append(',')
                append(metric.samples).append(',').append(metric.distinctDays).append(',')
                append(decimal(metric.predictedWinRate)).append(',')
                append(decimal(metric.actualWinRate)).append(',')
                append(decimal(metric.brierScore)).append(',')
                appendLine(decimal(metric.averageNetReturnPercent))
            }
        }
    }

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.8f", value)

    private fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' })
        "\"${value.replace("\"", "\"\"")}\"" else value
}