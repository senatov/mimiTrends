package org.senatov.mimitrends.charts

import javafx.scene.chart.BarChart
import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.StackPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.StringConverter
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MinuteBar
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

class TrendChartView : StackPane() {
    private val log = LoggerFactory.getLogger(TrendChartView::class.java)
    private val priceTimeAxis = NumberAxis()
    private val priceAxis = NumberAxis()
    private val volumeTimeAxis = CategoryAxis()
    private val volumeAxis = NumberAxis()
    private val chart = LineChart<Number, Number>(priceTimeAxis, priceAxis)
    private val volumeChart = BarChart<String, Number>(volumeTimeAxis, volumeAxis)
    private val progress = ProgressIndicator()

    init {
        log.debug(LogTag.UI, "init()")
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
        chart.setAnimated(false)
        priceAxis.isForceZeroInRange = false
        chart.createSymbols = false
        chart.legendVisibleProperty().set(false)
        chart.verticalGridLinesVisibleProperty().set(false)
        chart.styleClass += "trend-chart"
        volumeChart.setAnimated(false)
        volumeChart.legendVisibleProperty().set(false)
        volumeChart.verticalGridLinesVisibleProperty().set(false)
        volumeChart.prefHeight = 135.0
        volumeChart.minHeight = 100.0
        volumeChart.styleClass += "volume-chart"
        progress.maxWidth = 32.0
        progress.maxHeight = 32.0
        progress.isVisible = false
        val plots = VBox(4.0, chart, volumeChart).also {
            it.minHeight = 0.0
            it.maxHeight = Double.MAX_VALUE
            chart.minHeight = 0.0
            VBox.setVgrow(chart, Priority.ALWAYS)
        }
        children += listOf(plots, progress)
    }

    fun renderMinuteBars(symbol: String, bars: List<MinuteBar>, rangeLabel: String, priceMultiplier: Double = 1.0, currencySymbol: String = "$") {
        log.debug(LogTag.UI, "renderMinuteBars(symbol={}, bars={}, range={})", symbol, bars.size, rangeLabel)
        val chunkSize = ceil(bars.size / 180.0).toInt().coerceAtLeast(1)
        val visible = bars.chunked(chunkSize).map { chunk ->
            val first = chunk.first(); val last = chunk.last()
            MinuteBar(first.symbol, last.minuteEpochSeconds, first.open, chunk.maxOf { it.high }, chunk.minOf { it.low }, last.close, chunk.sumOf { it.volume })
        }
        val priceSeries = XYChart.Series<Number, Number>()
        val volumeSeries = XYChart.Series<String, Number>()
        val volumeTimeFormatter = DateTimeFormatter.ofPattern("dd MMM HH:mm")
            .withZone(ZoneId.systemDefault())
        visible.forEach { bar ->
            val timestamp = bar.minuteEpochSeconds.toDouble()
            priceSeries.data += XYChart.Data(timestamp, bar.close * priceMultiplier)
            volumeSeries.data += XYChart.Data(
                volumeTimeFormatter.format(Instant.ofEpochSecond(bar.minuteEpochSeconds)),
                bar.volume
            )
        }
        configureTimeAxis(priceTimeAxis, visible)
        chart.data.setAll(priceSeries); chart.createSymbols = visible.size < 2; chart.title = "$symbol · local minute prices · $currencySymbol"
        volumeChart.data.setAll(volumeSeries); volumeChart.title = "Trading volume · ${bars.size} collected minute bars"
        volumeChart.isVisible = true; volumeChart.isManaged = true
    }

    private fun configureTimeAxis(axis: NumberAxis, bars: List<MinuteBar>) {
        if (bars.isEmpty()) return

        val first = bars.minOf { it.minuteEpochSeconds }.toDouble()
        val last = bars.maxOf { it.minuteEpochSeconds }.toDouble()
        val dataSpan = (last - first).coerceAtLeast(0.0)
        val padding = max(60.0, dataSpan * 0.02)
        val displaySpan = max(120.0, dataSpan + padding * 2.0)
        val formatter = DateTimeFormatter.ofPattern(
            when {
                displaySpan <= 2 * 86_400 -> "HH:mm"
                displaySpan <= 14 * 86_400 -> "dd MMM HH:mm"
                displaySpan <= 180 * 86_400 -> "dd MMM"
                else -> "MMM yy"
            }
        ).withZone(ZoneId.systemDefault())

        axis.isAutoRanging = false
        axis.lowerBound = first - padding
        axis.upperBound = last + padding
        axis.tickUnit = max(60.0, displaySpan / 6.0)
        axis.tickLabelFormatter = object : StringConverter<Number>() {
            override fun toString(value: Number): String =
                formatter.format(Instant.ofEpochSecond(value.toLong()))

            override fun fromString(value: String): Number = 0
        }
    }

    fun setLoading(loading: Boolean) {
        log.debug(LogTag.UI, "setLoading(loading={})", loading)
        progress.isVisible = loading
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        chart.data.clear()
        volumeChart.data.clear()
    }
}
