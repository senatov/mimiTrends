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
        numberColumn("Price", { convertPrice(it.symbol, it.price) }) { "${currency.symbol}%,.2f".format(it) }
        val scoreColumn = numberColumn("Score", ScanResult::anomalyScore) { "%.2f×".format(it) }
        signalColumn(value = ScanResult::signalSource)
        numberColumn("When", { it.signalAgeMinutes.toDouble() }) {
            when (it.toInt()) { 0 -> "latest"; 1 -> "1m ago"; else -> "${it.toInt()}m ago" }
        }
        numberColumn("Δ 1m", ScanResult::windowChangePercent, ::percent)
        numberColumn("Jump Z", ScanResult::priceAnomaly) { "%.2fσ".format(it) }
        numberColumn("Range Z", ScanResult::rangeAnomaly) { "%.2fσ".format(it) }
        numberColumn("Volume Z", ScanResult::volumeAnomaly) { "%.2fσ".format(it) }
        numberColumn("RVOL", ScanResult::relativeVolume) { "%.2f×".format(it) }
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
        children += listOf(header, table)
        VBox.setVgrow(table, Priority.ALWAYS)
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
    }

    fun beginScan(number: Int, total: Int, symbols: List<String>) {
        log.debug(LogTag.UI, "beginScan(number={}, total={}, symbols={})", number, total, symbols.size)
        countdown?.stop(); stagedRows.clear(); scanning = true
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
            isResizable = true
            isReorderable = true
            prefWidth = 115.0
            minWidth = 55.0
            table.columns += this
        }
    }

    private fun numberColumn(
        title: String,
        value: (ScanResult) -> Double,
        format: (Double) -> String
    ): TableColumn<ScanResult, Number> {
        log.debug(LogTag.UI, "numberColumn(title={})", title)
        return TableColumn<ScanResult, Number>(title).apply {
            setCellValueFactory { ReadOnlyDoubleWrapper(value(it.value)) }
            setCellFactory { object : TableCell<ScanResult, Number>() {
                override fun updateItem(item: Number?, empty: Boolean) {
                    super.updateItem(item, empty); text = if (empty || item == null) null else format(item.toDouble())
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
