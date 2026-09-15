package org.senatov.mimitrends.charts

import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.time.Millisecond
import org.jfree.data.time.TimeSeries
import org.jfree.data.time.TimeSeriesCollection
import java.awt.BasicStroke
import java.awt.Color
import java.util.Date
import java.awt.geom.Ellipse2D

internal class MarketTrendOverlay(private val plot: XYPlot) {
    private val renderer = XYLineAndShapeRenderer(true, false).apply {
        setSeriesPaint(0, Color(23, 125, 190, 210))
        setSeriesStroke(0, BasicStroke(1.8f))
        setSeriesPaint(1, Color(224, 124, 31, 220))
        setSeriesStroke(1, BasicStroke(1.8f))
        setSeriesPaint(2, Color(92, 103, 116, 190))
        setSeriesStroke(
            2, BasicStroke(
                2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                0f, floatArrayOf(6f, 4f), 0f
            )
        )
    }

    fun render(timeline: ChartTimeline, priceMultiplier: Double) {
        val dataset = TimeSeriesCollection()
        addEma(dataset, timeline, FAST_EMA_BARS, "EMA 9", priceMultiplier)
        addEma(dataset, timeline, SLOW_EMA_BARS, "EMA 21", priceMultiplier)
        addRegression(dataset, timeline, priceMultiplier)
        if (hasSparseQuotes(timeline)) {
            val index = dataset.seriesCount
            val quotes = TimeSeries("Observed price · dotted gaps")
            timeline.actualBars.indices.forEach { i ->
                quotes.add(
                    Millisecond(Date(timeline.plottedBars[i].minuteEpochSeconds * 1_000)),
                    timeline.actualBars[i].close * priceMultiplier
                )
            }
            dataset.addSeries(quotes)
            renderer.setSeriesPaint(index, Color(48, 113, 112))
            renderer.setSeriesStroke(
                index, BasicStroke(
                    1.6f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 0f, floatArrayOf(2f, 4f), 0f
                )
            )
            renderer.setSeriesShapesVisible(index, true)
            renderer.setSeriesShape(index, Ellipse2D.Double(-2.5, -2.5, 5.0, 5.0))
        }
        plot.setRenderer(DATASET_INDEX, renderer)
        plot.setDataset(DATASET_INDEX, dataset)
    }

    fun clear() {
        plot.setDataset(DATASET_INDEX, null)
        plot.setRenderer(DATASET_INDEX, null)
    }

    private fun addEma(
        dataset: TimeSeriesCollection,
        timeline: ChartTimeline,
        period: Int,
        label: String,
        multiplier: Double
    ) {
        if (timeline.actualBars.size < 2) return
        val values = TrendSeriesCalculator.ema(timeline.actualBars.map { it.close * multiplier }, period)
        TimeSeries(label).apply {
            timeline.actualBars.indices.forEach { index ->
                add(Millisecond(Date(timeline.plottedBars[index].minuteEpochSeconds * 1_000)), values[index])
            }
            dataset.addSeries(this)
        }
    }

    private fun addRegression(
        dataset: TimeSeriesCollection,
        timeline: ChartTimeline,
        multiplier: Double
    ) {
        val start = (timeline.actualBars.size - REGRESSION_BARS).coerceAtLeast(0)
        val actual = timeline.actualBars.subList(start, timeline.actualBars.size)
        val plotted = timeline.plottedBars.subList(start, timeline.plottedBars.size)
        if (actual.size < 2) return
        val prices = actual.map { it.close * multiplier }
        val trend = TrendSeriesCalculator.linearRegression(prices)
        TimeSeries("Trend 30").apply {
            add(Millisecond(Date(plotted.first().minuteEpochSeconds * 1_000)), trend.first())
            add(Millisecond(Date(plotted.last().minuteEpochSeconds * 1_000)), trend.last())
            dataset.addSeries(this)
        }
    }

    internal fun hasSparseQuotes(timeline: ChartTimeline): Boolean =
        timeline.actualBars.isNotEmpty() && timeline.actualBars.count { it.high == it.low } * 2 >= timeline.actualBars.size

    private companion object {
        const val DATASET_INDEX = 1
        const val FAST_EMA_BARS = 9
        const val SLOW_EMA_BARS = 21
        const val REGRESSION_BARS = 30
    }
}