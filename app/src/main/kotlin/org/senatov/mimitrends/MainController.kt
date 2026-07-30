package org.senatov.mimitrends

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.chart.CategoryAxis
import javafx.scene.chart.LineChart
import javafx.scene.chart.NumberAxis
import javafx.scene.chart.XYChart
import javafx.scene.control.*
import javafx.scene.layout.*
import org.senatov.mimitrends.api.FinnhubClient
import org.senatov.mimitrends.model.MarketSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException

class MainController(private val apiKey: String?) {
    private val symbolField = TextField("AAPL")
    private val rangeBox = ComboBox(FXCollections.observableArrayList("1M", "3M", "6M", "1Y"))
    private val refreshButton = Button("↻  Refresh")
    private val statusLabel = Label()
    private val titleLabel = Label("AAPL")
    private val priceLabel = Label("—")
    private val changeLabel = Label("Waiting for data")
    private val highValue = Label("—")
    private val lowValue = Label("—")
    private val openValue = Label("—")
    private val previousValue = Label("—")
    private val progress = ProgressIndicator()
    private val chart = LineChart<String, Number>(CategoryAxis(), NumberAxis())
    private val chartStack = StackPane(chart, progress)

    fun createView(): Parent {
        rangeBox.value = "3M"
        symbolField.promptText = "Ticker, e.g. AAPL"
        symbolField.prefColumnCount = 12
        symbolField.setOnAction { refresh() }
        refreshButton.styleClass += "primary-button"
        refreshButton.setOnAction { refresh() }
        progress.maxWidth = 32.0
        progress.maxHeight = 32.0
        progress.isVisible = false

        val toolbar = HBox(
            8.0,
            Label("Symbol"),
            symbolField,
            Separator(),
            Label("Range"),
            rangeBox,
            spacer(),
            Label("Finnhub"),
            refreshButton
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "main-toolbar"
        }

        titleLabel.styleClass += "symbol-title"
        priceLabel.styleClass += "price"
        changeLabel.styleClass += "change"
        val heading = VBox(3.0, titleLabel, HBox(12.0, priceLabel, changeLabel).apply {
            alignment = Pos.BASELINE_LEFT
        })

        val metrics = HBox(
            10.0,
            metricCard("Open", openValue),
            metricCard("Day high", highValue),
            metricCard("Day low", lowValue),
            metricCard("Prev. close", previousValue)
        )

        chart.setAnimated(false)
        chart.createSymbols = false
        chart.legendVisibleProperty().set(false)
        chart.verticalGridLinesVisibleProperty().set(false)
        chart.styleClass += "trend-chart"
        VBox.setVgrow(chartStack, Priority.ALWAYS)

        val content = VBox(18.0, heading, metrics, chartStack).apply {
            padding = Insets(22.0, 24.0, 16.0, 24.0)
            VBox.setVgrow(chartStack, Priority.ALWAYS)
        }

        val statusBar = HBox(statusLabel).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "status-bar"
        }
        val root = BorderPane(content, toolbar, null, statusBar, null)
        root.styleClass += "app-root"

        if (apiKey == null) {
            setStatus("Add FINNHUB_API_KEY to the environment or a local .env file", true)
            refreshButton.isDisable = true
        } else {
            Platform.runLater(::refresh)
        }
        return root
    }

    private fun refresh() {
        val key = apiKey ?: return
        val symbol = symbolField.text.trim().uppercase()
        if (symbol.isEmpty()) {
            setStatus("Enter a market symbol", true)
            return
        }
        setLoading(true)
        setStatus("Loading $symbol…", false)
        FinnhubClient(key).loadSnapshot(symbol, selectedDays())
            .whenComplete { snapshot: MarketSnapshot?, error: Throwable? ->
                Platform.runLater {
                    setLoading(false)
                    if (error != null) {
                        val cause = (error as? CompletionException)?.cause ?: error
                        setStatus(cause.message ?: "Could not load market data", true)
                    } else if (snapshot != null) {
                        showSnapshot(snapshot)
                    } else {
                        setStatus("Finnhub returned an empty response", true)
                    }
                }
            }
    }

    private fun showSnapshot(snapshot: MarketSnapshot) {
        val quote = snapshot.quote
        titleLabel.text = snapshot.symbol
        priceLabel.text = "\$${"%,.2f".format(quote.current)}"
        changeLabel.text = "%+.2f  (%+.2f%%)".format(quote.change, quote.percentChange)
        changeLabel.styleClass.removeAll("gain", "loss")
        changeLabel.styleClass += if (quote.change >= 0) "gain" else "loss"
        openValue.text = money(quote.open)
        highValue.text = money(quote.high)
        lowValue.text = money(quote.low)
        previousValue.text = money(quote.previousClose)

        val formatter = DateTimeFormatter.ofPattern("dd MMM").withZone(ZoneId.systemDefault())
        val series = XYChart.Series<String, Number>()
        series.data += snapshot.candles.map {
            XYChart.Data(formatter.format(Instant.ofEpochSecond(it.timestampSeconds)), it.close)
        }
        chart.data.setAll(series)
        chart.title = "${snapshot.symbol} · ${rangeBox.value}"
        setStatus(
            if (snapshot.candles.size <= 30) "Live quote loaded · chart may use quote-derived fallback data"
            else "${snapshot.candles.size} daily closes loaded",
            false
        )
    }

    private fun metricCard(caption: String, value: Label): Node =
        VBox(5.0, Label(caption).apply { styleClass += "metric-caption" }, value.apply {
            styleClass += "metric-value"
        }).apply {
            styleClass += "metric-card"
            HBox.setHgrow(this, Priority.ALWAYS)
            maxWidth = Double.MAX_VALUE
        }

    private fun selectedDays(): Long = when (rangeBox.value) {
        "1M" -> 30
        "6M" -> 180
        "1Y" -> 365
        else -> 90
    }

    private fun setLoading(value: Boolean) {
        progress.isVisible = value
        refreshButton.isDisable = value
        symbolField.isDisable = value
        rangeBox.isDisable = value
    }

    private fun setStatus(message: String, error: Boolean) {
        statusLabel.text = message
        statusLabel.styleClass.removeAll("status-error")
        if (error) statusLabel.styleClass += "status-error"
    }

    private fun money(value: Double) = if (value > 0) "\$${"%,.2f".format(value)}" else "—"

    private fun spacer() = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
}