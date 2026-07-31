package org.senatov.mimitrends.charts

import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.BarChart
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.StackPane
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MarketSnapshot
import org.senatov.mimitrends.model.MinuteBar
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

class TrendChartView : StackPane() {
    private val log = LoggerFactory.getLogger(TrendChartView::class.java)
    private val chart = LineChart<String, Number>(CategoryAxis(), NumberAxis())
    private val volumeChart = BarChart<String, Number>(CategoryAxis(), NumberAxis())
    private val progress = ProgressIndicator()
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM").withZone(ZoneId.systemDefault())

    init {
        log.debug(LogTag.UI, "init()")
        chart.setAnimated(false)
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
        val plots = VBox(4.0, chart, volumeChart).also { VBox.setVgrow(chart, Priority.ALWAYS) }
        children += listOf(plots, progress)
    }

    fun render(snapshot: MarketSnapshot, rangeLabel: String, priceMultiplier: Double = 1.0, currencySymbol: String = "$") {
        log.debug(LogTag.UI, "render(symbol={}, points={}, range={})", snapshot.symbol, snapshot.candles.size, rangeLabel)
        val series = XYChart.Series<String, Number>()
        series.data += snapshot.candles.map { candle ->
            XYChart.Data(dateFormatter.format(Instant.ofEpochSecond(candle.timestampSeconds)), candle.close * priceMultiplier)
        }
        chart.data.setAll(series)
        chart.createSymbols = snapshot.candles.size < 2
        chart.title = "${snapshot.symbol} · $rangeLabel · $currencySymbol"
        volumeChart.data.clear()
        volumeChart.isVisible = false
        volumeChart.isManaged = false
    }

    fun renderMinuteBars(symbol: String, bars: List<MinuteBar>, rangeLabel: String, priceMultiplier: Double = 1.0, currencySymbol: String = "$") {
        log.debug(LogTag.UI, "renderMinuteBars(symbol={}, bars={}, range={})", symbol, bars.size, rangeLabel)
        val chunkSize = ceil(bars.size / 180.0).toInt().coerceAtLeast(1)
        val visible = bars.chunked(chunkSize).map { chunk ->
            val first = chunk.first(); val last = chunk.last()
            MinuteBar(first.symbol, last.minuteEpochSeconds, first.open, chunk.maxOf { it.high }, chunk.minOf { it.low }, last.close, chunk.sumOf { it.volume })
        }
        val priceSeries = XYChart.Series<String, Number>()
        val volumeSeries = XYChart.Series<String, Number>()
        val minuteFormatter = DateTimeFormatter.ofPattern(when (rangeLabel) {
            "1D" -> "HH:mm"
            "5D" -> "dd MMM HH:mm"
            else -> "dd MMM"
        }).withZone(ZoneId.systemDefault())
        visible.forEach { bar ->
            val label = minuteFormatter.format(Instant.ofEpochSecond(bar.minuteEpochSeconds))
            priceSeries.data += XYChart.Data(label, bar.close * priceMultiplier)
            volumeSeries.data += XYChart.Data(label, bar.volume)
        }
        chart.data.setAll(priceSeries); chart.createSymbols = visible.size < 2; chart.title = "$symbol · local minute prices · $currencySymbol"
        volumeChart.data.setAll(volumeSeries); volumeChart.title = "Trading volume · ${bars.size} collected minute bars"
        volumeChart.isVisible = true; volumeChart.isManaged = true
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
