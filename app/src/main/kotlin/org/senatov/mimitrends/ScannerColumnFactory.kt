package org.senatov.mimitrends

import javafx.application.Platform
import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyLongWrapper
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.geometry.Pos
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.Tooltip
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.util.Duration
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.ScanResult
import java.io.ByteArrayInputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer

internal class ScannerColumnFactory(
    private val table: TableView<ScanResult>,
    private val loadProfile: ((String) -> CompletableFuture<CompanyProfile>)?
) {
    private val logoImages = ConcurrentHashMap<String, Image>()
    private val companyNames = ConcurrentHashMap<String, String>()
    var onContentChanged: () -> Unit = {}

    fun companyName(result: ScanResult): String = companyNames[result.symbol] ?: result.symbol

    fun signal(title: String, value: (ScanResult) -> String): TableColumn<ScanResult, String> =
        TableColumn<ScanResult, String>(title).apply {
            setCellValueFactory { ReadOnlyObjectWrapper(value(it.value)) }
            setCellFactory {
                object : TableCell<ScanResult, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        val result = tableRow?.item
                        if (empty || item == null || result == null) {
                            text = null; style = ""; tooltip = null
                            return
                        }
                        val visual = signalVisual(result)
                        text = item
                        style = "-fx-text-fill: ${visual.color}; -fx-font-weight: ${visual.weight};"
                        tooltip = Tooltip(visual.description).apply { showDelay = Duration.millis(450.0) }
                    }
                }
            }
            configure(115.0, 55.0)
        }

    fun metric(
        title: String,
        sortValue: (ScanResult) -> Double,
        metric: (ScanResult) -> SignalMetric
    ): TableColumn<ScanResult, ScanResult> = TableColumn<ScanResult, ScanResult>(title).apply {
        setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
        comparator = Comparator { left, right -> sortValue(left).compareTo(sortValue(right)) }
        isSortable = true
        setCellFactory {
            object : TableCell<ScanResult, ScanResult>() {
                override fun updateItem(item: ScanResult?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null; style = ""; tooltip = null
                        return
                    }
                    val presentation = metric(item)
                    text = presentation.label
                    style = "-fx-text-fill: ${presentation.color}; -fx-font-weight: ${presentation.weight};"
                    tooltip = Tooltip(presentation.details).apply { showDelay = Duration.millis(350.0) }
                }
            }
        }
        configure(125.0, 82.0)
    }

    fun number(
        title: String,
        value: (ScanResult) -> Double,
        format: (Double) -> String
    ): TableColumn<ScanResult, Number> = TableColumn<ScanResult, Number>(title).apply {
        setCellValueFactory { ReadOnlyDoubleWrapper(value(it.value)) }
        comparator = Comparator { left, right -> left.toDouble().compareTo(right.toDouble()) }
        isSortable = true
        setCellFactory {
            object : TableCell<ScanResult, Number>() {
                override fun updateItem(item: Number?, empty: Boolean) {
                    super.updateItem(item, empty)
                    val numericValue = item?.toDouble()
                    text = if (empty || numericValue == null) null
                    else if (numericValue.isFinite()) format(numericValue) else "—"
                }
            }
        }
        configure(115.0, 55.0)
    }

    fun updated(format: (Long) -> String): TableColumn<ScanResult, Number> =
        TableColumn<ScanResult, Number>("Updated").apply {
            setCellValueFactory { ReadOnlyLongWrapper(it.value.updatedAtMillis) }
            setCellFactory {
                object : TableCell<ScanResult, Number>() {
                    override fun updateItem(item: Number?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else format(item.toLong())
                    }
                }
            }
            configure(105.0, 75.0)
        }

    fun symbol(): TableColumn<ScanResult, String> = TableColumn<ScanResult, String>("Symbol").apply {
        setCellValueFactory { ReadOnlyObjectWrapper(it.value.symbol) }
        setCellFactory {
            object : TableCell<ScanResult, String>() {
                private var renderedSymbol: String? = null

                override fun updateItem(symbol: String?, empty: Boolean) {
                    super.updateItem(symbol, empty)
                    if (empty || symbol == null) {
                        renderedSymbol = null; text = null; graphic = null; tooltip = null
                        return
                    }
                    renderedSymbol = symbol
                    text = symbol
                    contentDisplay = ContentDisplay.LEFT
                    graphic = logoBadge(symbol, null, 22.0)
                    tooltip = companyTooltip(symbol, null)
                    loadProfile?.invoke(symbol)?.whenComplete(
                        BiConsumer<CompanyProfile?, Throwable?> { profile, error ->
                        if (error == null && profile != null) Platform.runLater {
                            val displayName = CompanySearchTerm.normalizeDisplay(profile.name)
                            companyNames[symbol] = displayName
                            if (renderedSymbol == symbol && item == symbol) {
                                text = displayName
                                graphic = logoBadge(symbol, profile.logoBytes, 22.0)
                                tooltip = companyTooltip(symbol, profile.copy(name = displayName))
                                onContentChanged()
                            }
                        }
                    })
                }
            }
        }
        configure(210.0, 120.0)
    }

    private fun <T> TableColumn<ScanResult, T>.configure(preferred: Double, minimum: Double) {
        isResizable = true
        isReorderable = true
        prefWidth = preferred
        minWidth = minimum
        table.columns += this
    }

    private fun signalVisual(result: ScanResult): SignalVisual {
        val down = result.signalSource.contains('↓')
        val directionalColor = if (down) "#c43d4b" else "#138a55"
        val weakColor = if (down) "#a9787d" else "#668b72"
        return when {
            result.signalAgeMinutes > 0 -> SignalVisual("#7b8189", 400, "Old signal · ${result.signalAgeMinutes} minute(s) ago")
            result.signalSource.contains("relaxed", true) -> SignalVisual("#ad7100", 500, "Questionable signal · accepted only by relaxed statistical thresholds")
            result.signalSource.startsWith("Recovery breakout") ->
                SignalVisual("#2f7f61", 600, "Fresh breakout after recovery consolidation · earlier decline remains a risk factor")
            result.signalSource.startsWith("Recovery rise") ->
                SignalVisual("#2f7f61", 600, "Continuing recovery rise · earlier decline remains a risk factor")
            (result.signalSource.startsWith("Trend") || result.signalSource.startsWith("Steady rise")) &&
                kotlin.math.abs(result.windowChangePercent) < 0.90 ->
                SignalVisual(weakColor, 500, "Weak current trend · direction is active but close to the minimum threshold")
            result.signalSource.startsWith("Trend") || result.signalSource.startsWith("Steady rise") ->
                SignalVisual(directionalColor, 500, "Rising pattern across the measured window · continuation is not implied")
            result.anomalyScore < 1.25 -> SignalVisual("#ad7100", 500, "Questionable signal · low composite confidence")
            else -> SignalVisual(directionalColor, 600,
                if (down) "Current downward anomaly · direction is descriptive, not a forecast"
                else "Current upward anomaly · direction is descriptive, not a forecast")
        }
    }

    private fun companyTooltip(symbol: String, profile: CompanyProfile?): Tooltip {
        val details = VBox(2.0,
            Label(profile?.name ?: "Loading company details…").apply { styleClass += "company-tooltip-name" },
            Label("Ticker: $symbol").apply { styleClass += "company-tooltip-exchange" },
            Label("Exchange: ${profile?.exchange ?: "Loading exchange…"}").apply { styleClass += "company-tooltip-exchange" }
        )
        return Tooltip().apply {
            graphic = HBox(9.0, logoBadge(symbol, profile?.logoBytes, 38.0), details).apply { alignment = Pos.CENTER_LEFT }
            showDelay = Duration.seconds(2.0)
            hideDelay = Duration.millis(150.0)
            styleClass += "company-tooltip"
        }
    }

    private fun logoBadge(symbol: String, bytes: ByteArray?, size: Double): StackPane {
        val placeholder = Label(symbol.take(1)).apply {
            minWidth = size; prefWidth = size; maxWidth = size
            minHeight = size; prefHeight = size; maxHeight = size
            alignment = Pos.CENTER
            style = "-fx-background-color: #dce5f0; -fx-background-radius: ${size / 2}; -fx-text-fill: #17365f; -fx-font-weight: 500;"
        }
        return StackPane(placeholder).apply {
            minWidth = size; prefWidth = size; maxWidth = size
            minHeight = size; prefHeight = size; maxHeight = size
            bytes?.let { logoBytes ->
                val image = logoImages.computeIfAbsent(symbol) { Image(ByteArrayInputStream(logoBytes)) }
                children += ImageView(image).apply {
                    fitWidth = size; fitHeight = size; isPreserveRatio = true; isSmooth = true
                    styleClass += "company-logo"
                }
            }
        }
    }

    private data class SignalVisual(val color: String, val weight: Int, val description: String)
}
