package org.senatov.mimitrends.charts

import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.StackPane
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.MarketSnapshot
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TrendChartView : StackPane() {
    private val log = LoggerFactory.getLogger(TrendChartView::class.java)
    private val chart = LineChart<String, Number>(CategoryAxis(), NumberAxis())
    private val progress = ProgressIndicator()
    private val dateFormatter = DateTimeFormatter.ofPattern("dd MMM").withZone(ZoneId.systemDefault())

    init {
        log.debug(LogTag.UI, "init()")
        chart.setAnimated(false)
        chart.createSymbols = false
        chart.legendVisibleProperty().set(false)
        chart.verticalGridLinesVisibleProperty().set(false)
        chart.styleClass += "trend-chart"
        progress.maxWidth = 32.0
        progress.maxHeight = 32.0
        progress.isVisible = false
        children += listOf(chart, progress)
    }

    fun render(snapshot: MarketSnapshot, rangeLabel: String) {
        log.debug(LogTag.UI, "render(symbol={}, points={}, range={})", snapshot.symbol, snapshot.candles.size, rangeLabel)
        val series = XYChart.Series<String, Number>()
        series.data += snapshot.candles.map { candle ->
            XYChart.Data(dateFormatter.format(Instant.ofEpochSecond(candle.timestampSeconds)), candle.close)
        }
        chart.data.setAll(series)
        chart.createSymbols = snapshot.candles.size < 2
        chart.title = "${snapshot.symbol} · $rangeLabel"
    }

    fun setLoading(loading: Boolean) {
        log.debug(LogTag.UI, "setLoading(loading={})", loading)
        progress.isVisible = loading
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        chart.data.clear()
    }
}
