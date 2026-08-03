package org.senatov.mimitrends

import javafx.application.Platform
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.ScaleTransition
import javafx.animation.Timeline
import javafx.collections.FXCollections
import javafx.collections.transformation.SortedList
import javafx.geometry.Pos
import javafx.geometry.Insets
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
import javafx.scene.layout.Region
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
    private val marketHoursTitle = Label().apply { styleClass += "market-hours-title" }
    private val marketHoursLabel = Label().apply { styleClass += "market-hours-list" }
    private val brokerHoursTitle = Label("SCALABLE VENUES").apply { styleClass += "market-hours-title" }
    private val brokerHoursLabel = Label().apply { styleClass += "market-hours-list" }
    private val marketHoursPanel = VBox(4.0, marketHoursTitle, marketHoursLabel,
        Region().apply { minHeight = 5.0 }, brokerHoursTitle, brokerHoursLabel).apply {
        alignment = Pos.TOP_LEFT
        minWidth = 270.0
        prefWidth = 270.0
        maxWidth = 270.0
        maxHeight = Region.USE_PREF_SIZE
        isMouseTransparent = true
        styleClass += "market-hours-panel"
    }
    private val marketClosedFooter = StackPane(closeMarketOverlayButton).apply {
        alignment = Pos.BOTTOM_CENTER
        maxWidth = Double.MAX_VALUE
        maxHeight = Region.USE_PREF_SIZE
        styleClass += "market-closed-footer"
    }
    val marketClosedOverlay = StackPane(
        ImageView(Image(requireNotNull(javaClass.getResourceAsStream("/images/sleeping-dog-market-closed.png")))).apply {
            fitWidth = 590.0; fitHeight = 500.0; isPreserveRatio = true
            styleClass += "market-closed-dog"
        },
        VBox(
            7.0,
            Label("ALL SELECTED MARKETS ARE CLOSED").apply { styleClass += "market-closed-title" },
            marketClosedSubtitle
        ).apply {
            alignment = Pos.TOP_CENTER
            styleClass += "market-closed-content"
        },
        marketHoursPanel,
        marketClosedFooter
    ).apply {
        alignment = Pos.CENTER
        maxWidth = 680.0
        maxHeight = 570.0
        prefWidth = 680.0
        prefHeight = 570.0
        isVisible = false
        isManaged = false
        styleClass += "market-closed-overlay"
        StackPane.setAlignment(marketHoursPanel, Pos.TOP_LEFT)
        StackPane.setMargin(marketHoursPanel, Insets(108.0, 0.0, 0.0, 28.0))
        StackPane.setAlignment(marketClosedFooter, Pos.BOTTOM_CENTER)
        marketClosedFooter.prefHeightProperty().bind(heightProperty().multiply(0.15))
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
    private val columnFactory = ScannerColumnFactory(table, loadProfile)
    private val autoFitter: TableColumnAutoFitter<ScanResult>

    init {
        log.debug(LogTag.UI, "init()")
        val header = javafx.scene.layout.HBox(8.0, Label("Anomaly scanner").apply { styleClass += "scanner-title" },
            scanIndicator.apply { styleClass += "scanner-timer" }, cycleStatus.apply { styleClass += "scanner-cycle" },
            javafx.scene.layout.Region().also { javafx.scene.layout.HBox.setHgrow(it, Priority.ALWAYS) })
        sortedRows.comparatorProperty().bind(table.comparatorProperty())
        val symbol = columnFactory.symbol()
        val signal = columnFactory.signal("Signal", ScanResult::signalSource)
        val move = columnFactory.number("Move 10m", ScanResult::windowChangePercent, ::percent)
        val price = columnFactory.number("Price", { convertPrice(it.symbol, it.price) }) { "${currency.symbol}%,.2f".format(it) }
        val scoreColumn = columnFactory.metric("Anomaly", ScanResult::anomalyScore, SignalMetricPresentation::strength)
        val outcome = columnFactory.metric("Outcome", SignalMetricPresentation::outcomeSeverity, SignalMetricPresentation::outcome)
        val priceAction = columnFactory.metric("Price action", SignalMetricPresentation::priceActionSeverity, SignalMetricPresentation::priceAction)
        val volume = columnFactory.metric("Volume", SignalMetricPresentation::volumeSeverity, SignalMetricPresentation::volume)
        val age = columnFactory.signal("Age", ScanResult::signalWindowLabel)
        val feed = columnFactory.signal("Feed", ScanResult::dataStatus)
        val turnover = columnFactory.number("Turnover", { convertPrice(it.symbol, it.sessionTurnover) }, ::compactMoney)
        val updated = columnFactory.updated { time.format(Instant.ofEpochMilli(it)) }
        autoFitter = TableColumnAutoFitter(table, listOf(
            TableColumnAutoFitter.Spec(symbol, columnFactory::companyName, 120.0, 460.0, flexible = true, reserveWidth = 32.0),
            TableColumnAutoFitter.Spec(signal, ScanResult::signalSource, 74.0, 190.0),
            TableColumnAutoFitter.Spec(move, { percent(it.windowChangePercent) }, 82.0, 130.0, reserveWidth = 8.0),
            TableColumnAutoFitter.Spec(price, { "${currency.symbol}%,.2f".format(convertPrice(it.symbol, it.price)) }, 72.0, 135.0, reserveWidth = 8.0),
            TableColumnAutoFitter.Spec(scoreColumn, { SignalMetricPresentation.strength(it).label }, 88.0, 140.0),
            TableColumnAutoFitter.Spec(outcome, { SignalMetricPresentation.outcome(it).label }, 105.0, 165.0),
            TableColumnAutoFitter.Spec(priceAction, { SignalMetricPresentation.priceAction(it).label }, 100.0, 190.0),
            TableColumnAutoFitter.Spec(volume, { SignalMetricPresentation.volume(it).label }, 82.0, 160.0),
            TableColumnAutoFitter.Spec(age, ScanResult::signalWindowLabel, 72.0, 150.0),
            TableColumnAutoFitter.Spec(feed, ScanResult::dataStatus, 65.0, 130.0),
            TableColumnAutoFitter.Spec(turnover, { compactMoney(convertPrice(it.symbol, it.sessionTurnover)) }, 88.0, 145.0, reserveWidth = 8.0),
            TableColumnAutoFitter.Spec(updated, { time.format(Instant.ofEpochMilli(it.updatedAtMillis)) }, 88.0, 125.0, reserveWidth = 8.0)
        ))
        columnFactory.onContentChanged = autoFitter::request
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
                    MenuItem("Copy search keyword").apply { setOnAction { item?.let { copySearchKeyword(it) } } },
                    MenuItem("Copy ticker").apply { setOnAction { item?.let { copyText(it.symbol) } } }
                )
            }
        }
        table.setOnKeyPressed { event ->
            if (event.code == KeyCode.C && event.isShortcutDown) {
                table.selectionModel.selectedItem?.let(::copySearchKeyword)
                event.consume()
            }
        }
        table.minHeight = 0.0
        table.maxHeight = Double.MAX_VALUE
        table.styleClass += "scanner-table"
        tableContainer.children.setAll(table)
        marketClosedOverlay.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ESCAPE) {
                hideMarketClosedOverlay()
                event.consume()
            }
        }
        children += listOf(header, tableContainer)
        VBox.setVgrow(tableContainer, Priority.ALWAYS)
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
    }

    private fun copySearchKeyword(result: ScanResult) {
        copyText(CompanySearchTerm.from(columnFactory.companyName(result), result.symbol))
    }

    fun update(result: ScanResult) {
        log.debug(LogTag.UI, "update(symbol={}, score={})", result.symbol, result.anomalyScore)
        if (scanning) stagedRows[result.symbol] = result
    }

    fun applyPriorityResult(symbol: String, result: ScanResult?) {
        log.debug(LogTag.UI, "applyPriorityResult(symbol={}, present={})", symbol, result != null)
        val target = if (scanning) stagedRows else rows.associateByTo(linkedMapOf(), ScanResult::symbol)
        if (result == null) target.remove(symbol) else target[symbol] = result
        if (!scanning) {
            replaceRows(target.values)
            autoFitter.request()
        }
    }

    fun showSnapshot(results: Collection<ScanResult>, resultLimit: Int) {
        replaceRows(results.sortedByDescending(ScanResult::anomalyScore).take(resultLimit))
        autoFitter.request()
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        rows.clear(); stagedRows.clear(); scanning = false
        countdown?.stop(); hourglass?.stop(); scanIndicator.text = ""
        marketClosedOverlay.isVisible = false; marketClosedOverlay.isManaged = false
    }

    fun beginScan(number: Int, total: Int, symbols: List<String>) {
        log.debug(LogTag.UI, "beginScan(number={}, total={}, symbols={})", number, total, symbols.size)
        countdown?.stop(); stagedRows.clear(); scanning = true
        marketClosedOverlay.isVisible = false; marketClosedOverlay.isManaged = false
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
        replaceRows(stagedRows.values.sortedByDescending(ScanResult::anomalyScore).take(resultLimit))
        stagedRows.clear(); scanning = false; hourglass?.stop()
        autoFitter.request()
    }

    private fun replaceRows(replacements: Collection<ScanResult>) {
        val selectedSymbol = table.selectionModel.selectedItem?.symbol
        rows.setAll(replacements)
        table.sort()
        val retainedIndex = selectedSymbol?.let { symbol -> sortedRows.indexOfFirst { it.symbol == symbol } } ?: -1
        if (retainedIndex >= 0) {
            table.selectionModel.select(retainedIndex)
            return
        }
        sortedRows.firstOrNull()?.let { first ->
            table.selectionModel.select(0)
            table.scrollTo(0)
            log.debug(LogTag.UI, "selectionFallback(symbol={}, previous={})", first.symbol, selectedSymbol)
            onOpen(first)
        }
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

    fun showMarketClosed(
        snapshotSize: Int,
        persisted: Boolean,
        nextOpening: String,
        localZone: String,
        marketHours: List<String>,
        brokerHours: List<String>
    ) {
        cycleStatus.styleClass.remove("market-closed")
        cycleStatus.styleClass += "market-closed"
        cycleStatus.text = "ALL SELECTED MARKETS ARE CLOSED · " + when {
            snapshotSize == 0 -> "no saved results"
            persisted -> "$snapshotSize saved results · NOT LIVE"
            else -> "$snapshotSize cached close results · NOT LIVE"
        }
        cycleStatus.tooltip = Tooltip("The scanner is not presenting cached closing bars as current market signals.")
        marketClosedSubtitle.text = "Saved closing snapshot · scanner resumes $nextOpening"
        marketHoursTitle.text = "PRICE DATA MARKETS · $localZone"
        marketHoursLabel.text = marketHours.joinToString("\n")
        brokerHoursTitle.text = "SCALABLE VENUES · $localZone"
        brokerHoursLabel.text = brokerHours.joinToString("\n")
        marketHoursPanel.isVisible = marketHours.isNotEmpty() || brokerHours.isNotEmpty()
        marketHoursPanel.isManaged = marketHoursPanel.isVisible
        marketClosedOverlay.isVisible = true
        marketClosedOverlay.isManaged = true
        marketClosedOverlay.toFront()
        Platform.runLater { closeMarketOverlayButton.requestFocus() }
    }

    private fun hideMarketClosedOverlay() {
        marketClosedOverlay.isVisible = false
        marketClosedOverlay.isManaged = false
        table.requestFocus()
    }

    fun setCurrency(value: DisplayCurrency, converter: (String, Double) -> Double) {
        log.debug(LogTag.UI, "setCurrency(currency={})", value)
        currency = value; convertPrice = converter; table.refresh(); autoFitter.request()
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
        table.refresh(); autoFitter.request()
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
