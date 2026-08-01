package org.senatov.mimitrends

import javafx.application.Platform
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.ScaleTransition
import javafx.animation.Timeline
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyLongWrapper
import javafx.collections.FXCollections
import javafx.collections.transformation.SortedList
import javafx.geometry.Pos
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.control.*
import javafx.scene.input.MouseButton
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.CompanyProfile
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.DisplayCurrency
import org.senatov.mimitrends.model.TableAppearance
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiConsumer
import java.io.ByteArrayInputStream
import javafx.util.Duration

class ScannerPanel(
    private val onOpen: (ScanResult) -> Unit,
    private val loadProfile: ((String) -> CompletableFuture<CompanyProfile>)? = null
) : VBox(7.0) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val rows = FXCollections.observableArrayList<ScanResult>()
    private val sortedRows = SortedList(rows)
    private val table = TableView(sortedRows)
    private val tableContainer = StackPane()
    private val closeMarketOverlayButton = Button("Close").apply {
        styleClass += "market-closed-close"
        setOnAction { hideMarketClosedOverlay() }
    }
    private val marketClosedSubtitle = Label("Saved closing snapshot · not live").apply {
        styleClass += "market-closed-subtitle"
    }
    private val closedMarketOverlay = StackPane(
        ImageView(Image(requireNotNull(javaClass.getResourceAsStream("/images/sleeping-dog-market-closed.png")))).apply {
            fitWidth = 560.0; fitHeight = 520.0; isPreserveRatio = true
            styleClass += "market-closed-dog"
        },
        VBox(
            12.0,
            Label("ALL SELECTED MARKETS ARE CLOSED").apply { styleClass += "market-closed-title" },
            marketClosedSubtitle,
            closeMarketOverlayButton
        ).apply {
            alignment = Pos.CENTER
            styleClass += "market-closed-content"
        }
    ).apply {
        alignment = Pos.CENTER
        maxWidth = 680.0
        isVisible = false
        isManaged = false
        styleClass += "market-closed-overlay"
    }
    private val empty = Label("Waiting for the first local/Yahoo scan…")
    private val cycleStatus = Label()
    private val scanIndicator = Label()
    private val stagedRows = linkedMapOf<String, ScanResult>()
    private var scanning = false
    private var countdown: Timeline? = null
    private var hourglass: Timeline? = null
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private var currency = DisplayCurrency.EUR
    private var convertPrice: (String, Double) -> Double = { _, value -> value }
    private val logoImages = ConcurrentHashMap<String, Image>()
    private val companyNames = ConcurrentHashMap<String, String>()

    init {
        log.debug(LogTag.UI, "init()")
        val header = javafx.scene.layout.HBox(8.0, Label("Anomaly scanner").apply { styleClass += "scanner-title" },
            scanIndicator.apply { styleClass += "scanner-timer" }, cycleStatus.apply { styleClass += "scanner-cycle" },
            javafx.scene.layout.Region().also { javafx.scene.layout.HBox.setHgrow(it, Priority.ALWAYS) })
        sortedRows.comparatorProperty().bind(table.comparatorProperty())
        symbolColumn()
        signalColumn("Signal", ScanResult::signalSource)
        numberColumn("Move 10m", ScanResult::windowChangePercent, ::percent)
        numberColumn("Price", { convertPrice(it.symbol, it.price) }) { "${currency.symbol}%,.2f".format(it) }
        val scoreColumn = readableMetricColumn("Strength", ScanResult::anomalyScore, ::strengthMetric)
        readableMetricColumn("Price action", { maxOfFinite(it.priceAnomaly, it.rangeAnomaly) }, ::priceActionMetric)
        readableMetricColumn("Volume", { maxOfFinite(it.volumeAnomaly, it.relativeVolume) }, ::volumeMetric)
        signalColumn("Age", ScanResult::signalWindowLabel)
        signalColumn("Feed", ScanResult::dataStatus)
        numberColumn("Turnover", { convertPrice(it.symbol, it.sessionTurnover) }, ::compactMoney)
        updatedColumn(ScanResult::updatedAtMillis) { time.format(Instant.ofEpochMilli(it)) }
        scoreColumn.sortType = TableColumn.SortType.DESCENDING
        table.sortOrder += scoreColumn
        table.setOnSort {
            log.debug(LogTag.UI, "tableSort(columns={})", table.sortOrder.joinToString { "${it.text}:${it.sortType}" })
        }
        table.placeholder = empty
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.fixedCellSize = 30.0
        table.setRowFactory {
            TableRow<ScanResult>().apply {
                setOnMouseClicked { e -> if (!isEmpty && e.button == MouseButton.PRIMARY && e.clickCount == 1) onOpen(item) }
                contextMenu = ContextMenu(
                    MenuItem("Copy company name").apply { setOnAction { item?.let { copyText(companyNames[it.symbol] ?: it.symbol) } } },
                    MenuItem("Copy ticker").apply { setOnAction { item?.let { copyText(it.symbol) } } }
                )
            }
        }
        table.setOnKeyPressed { event ->
            if (event.code == KeyCode.C && event.isShortcutDown) {
                table.selectionModel.selectedItem?.let { copyText(companyNames[it.symbol] ?: it.symbol) }
                event.consume()
            }
        }
        table.minHeight = 0.0
        table.maxHeight = Double.MAX_VALUE
        table.styleClass += "scanner-table"
        tableContainer.children.setAll(table, closedMarketOverlay)
        StackPane.setAlignment(closedMarketOverlay, Pos.CENTER)
        addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ESCAPE && closedMarketOverlay.isVisible) {
                hideMarketClosedOverlay()
                event.consume()
            }
        }
        children += listOf(header, tableContainer)
        VBox.setVgrow(tableContainer, Priority.ALWAYS)
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
    }

    fun update(result: ScanResult) {
        log.debug(LogTag.UI, "update(symbol={}, score={})", result.symbol, result.anomalyScore)
        if (scanning) stagedRows[result.symbol] = result
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        rows.clear(); stagedRows.clear(); scanning = false
        countdown?.stop(); hourglass?.stop(); scanIndicator.text = ""
        closedMarketOverlay.isVisible = false; closedMarketOverlay.isManaged = false
    }

    fun beginScan(number: Int, total: Int, symbols: List<String>) {
        log.debug(LogTag.UI, "beginScan(number={}, total={}, symbols={})", number, total, symbols.size)
        countdown?.stop(); stagedRows.clear(); scanning = true
        closedMarketOverlay.isVisible = false; closedMarketOverlay.isManaged = false
        cycleStatus.styleClass.remove("market-closed")
        cycleStatus.text = "Batch $number/$total · ${symbols.size} symbols"
        cycleStatus.tooltip = Tooltip(symbols.joinToString(", "))
        scanIndicator.text = "⏳ Scanning…"
        hourglass?.stop()
        var flipped = false
        hourglass = Timeline(KeyFrame(Duration.millis(450.0), {
            flipped = !flipped
            scanIndicator.text = if (flipped) "⌛ Scanning…" else "⏳ Scanning…"
        })).apply {
            cycleCount = Animation.INDEFINITE; play()
        }
    }

    fun completeScan(resultLimit: Int = 50) {
        log.debug(LogTag.UI, "completeScan(results={})", stagedRows.size)
        rows.setAll(stagedRows.values.sortedByDescending(ScanResult::anomalyScore).take(resultLimit))
        stagedRows.clear(); scanning = false; hourglass?.stop()
    }

    fun abortScan() {
        log.debug(LogTag.UI, "abortScan()")
        stagedRows.clear(); scanning = false; hourglass?.stop()
    }

    fun showCountdown(seconds: Long) {
        log.debug(LogTag.UI, "showCountdown(seconds={})", seconds)
        countdown?.stop(); hourglass?.stop()
        var remaining = seconds.coerceAtLeast(0)
        fun render() { scanIndicator.text = "Next scan  %02d:%02d".format(remaining / 60, remaining % 60) }
        render()
        countdown = Timeline(KeyFrame(Duration.seconds(1.0), {
            remaining = (remaining - 1).coerceAtLeast(0); render()
            ScaleTransition(Duration.millis(180.0), scanIndicator).apply {
                fromX = 1.0; fromY = 1.0; toX = 1.05; toY = 1.05
                cycleCount = 2; isAutoReverse = true; play()
            }
        })).apply { cycleCount = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); play() }
    }

    fun showMarketClosed(snapshotSize: Int, persisted: Boolean, nextOpening: String) {
        cycleStatus.styleClass.remove("market-closed")
        cycleStatus.styleClass += "market-closed"
        cycleStatus.text = "ALL SELECTED MARKETS ARE CLOSED · " + when {
            snapshotSize == 0 -> "no saved results"
            persisted -> "$snapshotSize saved results · NOT LIVE"
            else -> "$snapshotSize cached close results · NOT LIVE"
        }
        cycleStatus.tooltip = Tooltip("The scanner is not presenting cached closing bars as current market signals.")
        marketClosedSubtitle.text = "Saved closing snapshot · scanner resumes $nextOpening"
        closedMarketOverlay.isVisible = true
        closedMarketOverlay.isManaged = true
        Platform.runLater { closeMarketOverlayButton.requestFocus() }
    }

    private fun hideMarketClosedOverlay() {
        closedMarketOverlay.isVisible = false
        closedMarketOverlay.isManaged = false
        table.requestFocus()
    }

    fun setCurrency(value: DisplayCurrency, converter: (String, Double) -> Double) {
        log.debug(LogTag.UI, "setCurrency(currency={})", value)
        currency = value; convertPrice = converter; table.refresh()
    }

    fun setAppearance(value: TableAppearance) {
        log.debug(LogTag.UI, "setAppearance(font={}, size={})", value.fontFamily, value.fontSize)
        val safeFont = value.fontFamily.replace("\"", "")
        table.style = """
            -fx-font-family: "$safeFont";
            -fx-font-size: ${value.fontSize}px;
            -mimi-table-text: ${value.textColor};
            -mimi-row-even: ${value.evenRowColor};
            -mimi-row-odd: ${value.oddRowColor};
            -mimi-selection: ${value.selectionColor};
            -mimi-table-grid: ${value.gridColor};
        """.trimIndent()
        table.refresh()
    }

    private fun signalColumn(title: String = "Signal", value: (ScanResult) -> String): TableColumn<ScanResult, String> {
        log.debug(LogTag.UI, "signalColumn(title={})", title)
        return TableColumn<ScanResult, String>(title).apply {
            setCellValueFactory { ReadOnlyObjectWrapper(value(it.value)) }
            setCellFactory {
                object : TableCell<ScanResult, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        val result = tableRow?.item
                        if (empty || item == null || result == null) {
                            text = null
                            style = ""
                            tooltip = null
                            return
                        }
                        val visual = signalVisual(result)
                        text = item
                        style = "-fx-text-fill: ${visual.color}; -fx-font-weight: ${visual.weight};"
                        tooltip = Tooltip(visual.description).apply { showDelay = Duration.millis(450.0) }
                    }
                }
            }
            isResizable = true
            isReorderable = true
            prefWidth = 115.0
            minWidth = 55.0
            table.columns += this
        }
    }

    private fun signalVisual(result: ScanResult): SignalVisual {
        val down = result.signalSource.contains('↓')
        val directionalColor = if (down) "#c43d4b" else "#138a55"
        val weakColor = if (down) "#a9787d" else "#668b72"
        return when {
            result.signalAgeMinutes > 0 -> SignalVisual("#7b8189", 400,
                "Old signal · ${result.signalAgeMinutes} minute(s) ago")
            result.signalSource.contains("relaxed", ignoreCase = true) -> SignalVisual("#ad7100", 500,
                "Questionable signal · accepted only by relaxed statistical thresholds")
            result.signalSource.startsWith("Trend") && kotlin.math.abs(result.windowChangePercent) < 0.90 ->
                SignalVisual(weakColor, 500, "Weak current trend · direction is active but close to the minimum threshold")
            result.anomalyScore < 1.25 -> SignalVisual("#ad7100", 500,
                "Questionable signal · low composite confidence")
            else -> SignalVisual(directionalColor, 600,
                if (down) "Strong current downward movement" else "Strong current upward movement")
        }
    }

    private data class SignalVisual(val color: String, val weight: Int, val description: String)

    private fun readableMetricColumn(
        title: String,
        sortValue: (ScanResult) -> Double,
        metric: (ScanResult) -> ReadableMetric
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
                    val value = metric(item)
                    text = value.label
                    style = "-fx-text-fill: ${value.color}; -fx-font-weight: ${value.weight};"
                    tooltip = Tooltip(value.details).apply { showDelay = Duration.millis(350.0) }
                }
            }
        }
        isResizable = true; isReorderable = true; prefWidth = 125.0; minWidth = 82.0
        table.columns += this
    }

    private fun strengthMetric(result: ScanResult): ReadableMetric {
        val score = result.anomalyScore
        val label = when {
            score >= 6.0 -> "Extreme"
            score >= 4.0 -> "Strong"
            score >= 2.5 -> "Notable"
            else -> "Watch"
        }
        val color = when (label) {
            "Extreme" -> "#a92f3d"
            "Strong" -> "#b26012"
            "Notable" -> "#526f8a"
            else -> "#707981"
        }
        return ReadableMetric(label, color, if (score >= 4.0) 600 else 500,
            "Composite signal strength: %.2f×\nIncludes price anomaly, volume confirmation, candle quality and freshness.".format(score))
    }

    private fun priceActionMetric(result: ScanResult): ReadableMetric {
        if (!result.priceAnomaly.isFinite() && !result.rangeAnomaly.isFinite()) {
            val arrow = if (result.windowChangePercent < 0) "↓" else "↑"
            return ReadableMetric("Steady trend $arrow", "#3f6682", 500,
                "Persistent price trend over ${result.signalWindowLabel}; no single exceptional candle.")
        }
        val jump = result.priceAnomaly.takeIf(Double::isFinite) ?: 0.0
        val range = result.rangeAnomaly.takeIf(Double::isFinite) ?: 0.0
        val arrow = if (result.windowChangePercent < 0) "↓" else "↑"
        val (label, color) = when {
            jump >= 6.0 && range >= 6.0 -> "Extreme impulse $arrow" to "#a92f3d"
            range >= jump * 1.5 && range >= 3.5 -> "Volatile / unstable" to "#9a6717"
            jump >= 4.0 -> "Strong impulse $arrow" to if (arrow == "↑") "#137b50" else "#b23b48"
            else -> "Elevated move $arrow" to "#526f8a"
        }
        return ReadableMetric(label, color, if (jump >= 4.0 || range >= 5.0) 600 else 500,
            "Price jump: %.2fσ\nFull candle range: %.2fσ\n10-minute move: %+.2f%%".format(jump, range, result.windowChangePercent))
    }

    private fun volumeMetric(result: ScanResult): ReadableMetric {
        val rvol = result.relativeVolume.takeIf(Double::isFinite)
        val z = result.volumeAnomaly.takeIf(Double::isFinite)
        if (rvol == null && z == null) return ReadableMetric("Price-led", "#707981", 400,
            "Trend signal without a single-candle volume anomaly.")
        val level = when {
            (rvol ?: 0.0) >= 5.0 || (z ?: 0.0) >= 5.0 -> "Extreme"
            (rvol ?: 0.0) >= 3.0 || (z ?: 0.0) >= 3.0 -> "Strong"
            (rvol ?: 0.0) >= 1.8 || (z ?: 0.0) >= 2.0 -> "Elevated"
            else -> "Normal"
        }
        val label = rvol?.let { "$level %.1f×".format(it) } ?: level
        val color = when (level) {
            "Extreme" -> "#a92f3d"
            "Strong" -> "#b26012"
            "Elevated" -> "#526f8a"
            else -> "#707981"
        }
        return ReadableMetric(label, color, if (level in setOf("Extreme", "Strong")) 600 else 500,
            "Relative volume: ${rvol?.let { "%.2f×".format(it) } ?: "—"}\nVolume anomaly: ${z?.let { "%.2fσ".format(it) } ?: "—"}")
    }

    private fun maxOfFinite(first: Double, second: Double): Double =
        listOf(first, second).filter(Double::isFinite).maxOrNull() ?: Double.NEGATIVE_INFINITY

    private data class ReadableMetric(
        val label: String,
        val color: String,
        val weight: Int,
        val details: String
    )

    private fun numberColumn(
        title: String,
        value: (ScanResult) -> Double,
        format: (Double) -> String
    ): TableColumn<ScanResult, Number> {
        log.debug(LogTag.UI, "numberColumn(title={})", title)
        return TableColumn<ScanResult, Number>(title).apply {
            setCellValueFactory { ReadOnlyDoubleWrapper(value(it.value)) }
            comparator = Comparator { left, right -> left.toDouble().compareTo(right.toDouble()) }
            isSortable = true
            setCellFactory { object : TableCell<ScanResult, Number>() {
                override fun updateItem(item: Number?, empty: Boolean) {
                    super.updateItem(item, empty)
                    val value = item?.toDouble()
                    text = if (empty || value == null) null else if (value.isFinite()) format(value) else "—"
                }
            } }
            isResizable = true; isReorderable = true; prefWidth = 115.0; minWidth = 55.0
            table.columns += this
        }
    }

    private fun updatedColumn(
        value: (ScanResult) -> Long,
        format: (Long) -> String
    ): TableColumn<ScanResult, Number> = TableColumn<ScanResult, Number>("Updated").apply {
        setCellValueFactory { ReadOnlyLongWrapper(value(it.value)) }
        setCellFactory { object : TableCell<ScanResult, Number>() {
            override fun updateItem(item: Number?, empty: Boolean) {
                super.updateItem(item, empty); text = if (empty || item == null) null else format(item.toLong())
            }
        } }
        isResizable = true; isReorderable = true; prefWidth = 105.0; minWidth = 75.0
        table.columns += this
    }

    private fun symbolColumn() {
        log.debug(LogTag.UI, "symbolColumn()")
        table.columns += TableColumn<ScanResult, String>("Symbol").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value.symbol) }
            setCellFactory {
                object : TableCell<ScanResult, String>() {
                    private var renderedSymbol: String? = null

                    override fun updateItem(symbol: String?, empty: Boolean) {
                        super.updateItem(symbol, empty)
                        if (empty || symbol == null) {
                            renderedSymbol = null
                            text = null
                            graphic = null
                            tooltip = null
                            return
                        }

                        renderedSymbol = symbol
                        text = symbol
                        contentDisplay = ContentDisplay.LEFT
                        graphic = logoBadge(symbol, null, 22.0)
                        tooltip = companyTooltip(symbol, null)
                        loadProfile?.invoke(symbol)?.whenComplete(BiConsumer<CompanyProfile?, Throwable?> { profile, error ->
                            if (error == null && profile != null) Platform.runLater {
                                companyNames[symbol] = profile.name
                                if (renderedSymbol == symbol && item == symbol) {
                                    text = profile.name
                                    graphic = logoBadge(symbol, profile.logoBytes, 22.0)
                                    tooltip = companyTooltip(symbol, profile)
                                }
                            }
                        })
                    }
                }
            }
            isResizable = true
            isReorderable = true
            prefWidth = 210.0
            minWidth = 120.0
        }
    }

    private fun companyTooltip(symbol: String, profile: CompanyProfile?): Tooltip {
        log.debug(LogTag.UI, "companyTooltip(symbol={}, loaded={})", symbol, profile != null)
        val name = profile?.name ?: "Loading company details…"
        val exchange = profile?.exchange ?: "Loading exchange…"
        val details = VBox(2.0,
            Label(name).apply { styleClass += "company-tooltip-name" },
            Label("Ticker: $symbol").apply { styleClass += "company-tooltip-exchange" },
            Label("Exchange: $exchange").apply { styleClass += "company-tooltip-exchange" }
        )
        val card = HBox(9.0, logoBadge(symbol, profile?.logoBytes, 38.0), details).apply {
            alignment = Pos.CENTER_LEFT
        }
        return Tooltip().apply {
            graphic = card
            showDelay = Duration.seconds(2.0)
            hideDelay = Duration.millis(150.0)
            styleClass += "company-tooltip"
        }
    }

    private fun logoBadge(symbol: String, logoBytes: ByteArray?, size: Double): StackPane {
        log.debug(LogTag.UI, "logoBadge(symbol={}, hasLogo={}, size={})", symbol, logoBytes != null, size)
        val placeholder = Label(symbol.take(1)).apply {
            minWidth = size; prefWidth = size; maxWidth = size
            minHeight = size; prefHeight = size; maxHeight = size
            alignment = Pos.CENTER
            style = "-fx-background-color: #dce5f0; -fx-background-radius: ${size / 2}; -fx-text-fill: #17365f; -fx-font-weight: 500;"
        }
        return StackPane(placeholder).apply {
            minWidth = size; prefWidth = size; maxWidth = size
            minHeight = size; prefHeight = size; maxHeight = size
            logoBytes?.let { bytes ->
                val image = logoImages.computeIfAbsent(symbol) { Image(ByteArrayInputStream(bytes)) }
                children += ImageView(image).apply {
                    fitWidth = size; fitHeight = size; isPreserveRatio = true; isSmooth = true
                    styleClass += "company-logo"
                }
            }
        }
    }

    private fun percent(value: Double?) = value?.let { "%+.2f%%".format(it) } ?: "N/A"

    private fun copyText(value: String) {
        log.debug(LogTag.UI, "copyText(chars={})", value.length)
        Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(value) })
    }

    private fun compactMoney(value: Double): String = when {
        value >= 1_000_000_000 -> "${currency.symbol}%.1fB".format(value / 1_000_000_000)
        value >= 1_000_000 -> "${currency.symbol}%.1fM".format(value / 1_000_000)
        value >= 1_000 -> "${currency.symbol}%.1fK".format(value / 1_000)
        else -> "${currency.symbol}%,.0f".format(value)
    }
}
