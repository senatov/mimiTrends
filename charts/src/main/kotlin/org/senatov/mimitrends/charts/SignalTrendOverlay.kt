package org.senatov.mimitrends.charts

import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.time.Millisecond
import org.jfree.data.time.TimeSeries
import org.jfree.data.time.TimeSeriesCollection
import java.awt.BasicStroke
import java.awt.Color
import java.util.Date

internal class SignalTrendOverlay(private val plot: XYPlot) {
    private val renderer = XYLineAndShapeRenderer(true, false).apply {
        setSeriesPaint(0, Color(23, 125, 190, 210))
        setSeriesStroke(0, BasicStroke(2.0f))
        setSeriesPaint(1, Color(224, 124, 31, 220))
        setSeriesStroke(1, BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            0f, floatArrayOf(6f, 4f), 0f))
    }

    fun render(timeline: ChartTimeline, priceMultiplier: Double) {
        val dataset = TimeSeriesCollection()
        addTrend(dataset, timeline, FAST_TREND_BARS, "Fast 5m", priceMultiplier)
        addTrend(dataset, timeline, LOCAL_TREND_BARS, "Local 15m", priceMultiplier)
        plot.setRenderer(DATASET_INDEX, renderer)
        plot.setDataset(DATASET_INDEX, dataset)
    }

    fun clear() {
        plot.setDataset(DATASET_INDEX, null)
        plot.setRenderer(DATASET_INDEX, null)
    }

    private fun addTrend(
        dataset: TimeSeriesCollection,
        timeline: ChartTimeline,
        count: Int,
        label: String,
        multiplier: Double
    ) {
        val start = (timeline.actualBars.size - count).coerceAtLeast(0)
        val actual = timeline.actualBars.subList(start, timeline.actualBars.size)
        val plotted = timeline.plottedBars.subList(start, timeline.plottedBars.size)
        if (actual.size < 2) return
        val meanX = (actual.size - 1) / 2.0
        val prices = actual.map { it.close * multiplier }
        val meanY = prices.average()
        val denominator = actual.indices.sumOf { index -> (index - meanX) * (index - meanX) }
        val slope = if (denominator > 0.0) actual.indices.sumOf { index ->
            (index - meanX) * (prices[index] - meanY)
        } / denominator else 0.0
        val first = meanY - slope * meanX
        val last = meanY + slope * (actual.lastIndex - meanX)
        TimeSeries(label).apply {
            add(Millisecond(Date(plotted.first().minuteEpochSeconds * 1_000)), first)
            add(Millisecond(Date(plotted.last().minuteEpochSeconds * 1_000)), last)
            dataset.addSeries(this)
        }
    }

    private companion object {
        const val DATASET_INDEX = 1
        const val FAST_TREND_BARS = 5
        const val LOCAL_TREND_BARS = 15
    }
}
