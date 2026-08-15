package org.senatov.mimitrends

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.transformation.SortedList
import javafx.geometry.Pos
import javafx.geometry.Insets
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.control.*
import javafx.scene.input.MouseButton
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
    private val shortMovePanel: ShortMovePanel,
    savedColumns: String = "",
    initialTableDivider: Double = 0.68,
    private val loadProfile: ((String) -> CompletableFuture<CompanyProfile>)? = null,
    private val openStock: (String) -> Unit = {}
) : VBox(7.0) {
    internal var onInspect: (ScanResult) -> Unit = {}
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
    private val marketClosedTitle = Label("ALL SELECTED MARKETS ARE CLOSED").apply {
        styleClass += "market-closed-title"
    }
    private var closing = false
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
            marketClosedTitle,
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
    private val scanIndicator = ScanClockIndicator()
    private val stagedRows = linkedMapOf<String, ScanResult>()
    private val refreshingStatuses = mutableMapOf<String, String>()
    private val observationOverlay = MarketObservationOverlay()
    private var scanning = false
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private var currency = DisplayCurrency.EUR
    private var convertPrice: (String, Double) -> Double = { _, value -> value }
    private val columnFactory = ScannerColumnFactory(table, loadProfile)
    private val autoFitter: TableColumnAutoFitter<ScanResult>
    private val columnLayout: TableColumnLayout<ScanResult>
    private val tableSplit = SplitPane()

    init {
        log.debug(LogTag.UI, "init()")
        val header = javafx.scene.layout.HBox(8.0, Label("Anomaly signals").apply { styleClass += "table-section-title" },
            scanIndicator, cycleStatus.apply { styleClass += "scanner-cycle" },
            javafx.scene.layout.Region().also { javafx.scene.layout.HBox.setHgrow(it, Priority.ALWAYS) }).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "table-section-header"
        }
        sortedRows.comparatorProperty().bind(table.comparatorProperty())
        val freshness = columnFactory.freshness()
        val symbol = columnFactory.symbol()
        val signal = columnFactory.pattern()
        val move = columnFactory.number("Move 10m", ScanResult::windowChangePercent, ::percent)
        val price = columnFactory.number("Price", { convertPrice(it.symbol, it.price) }) { "${currency.symbol}%,.2f".format(it) }
        val scoreColumn = columnFactory.metric("Anomaly", ScanResult::anomalyScore, SignalMetricPresentation::strength)
        val outcome = columnFactory.metric("Outcome", SignalMetricPresentation::outcomeSeverity, SignalMetricPresentation::outcome)
        val priceAction = columnFactory.metric("Price action", SignalMetricPresentation::priceActionSeverity, SignalMetricPresentation::priceAction)
        val volume = columnFactory.metric("Volume", SignalMetricPresentation::volumeSeverity, SignalMetricPresentation::volume)
        val age = columnFactory.signal("Signal age", { SignalAgePresentation.label(it.signalAgeMinutes) }) {
            it.signalAgeMinutes.toDouble()
        }
        val turnover = columnFactory.number("Turnover", { convertPrice(it.symbol, it.sessionTurnover) }, ::compactMoney)
        val updated = columnFactory.updated { time.format(Instant.ofEpochMilli(it)) }
        listOf(freshness, symbol, signal, move, price, scoreColumn, outcome, priceAction, volume, age, turnover, updated)
            .zip(listOf("delay", "company", "pattern", "move", "price", "anomaly", "outcome", "price_action",
                "volume", "age", "turnover", "updated"))
            .forEach { (column, id) -> column.id = id }
        columnLayout = TableColumnLayout(table, savedColumns).also(TableColumnLayout<ScanResult>::install)
        autoFitter = TableColumnAutoFitter(table, listOf(
            TableColumnAutoFitter.Spec(freshness, { FeedFreshness.ageLabel(it.updatedAtMillis) }, 68.0, 96.0),
            TableColumnAutoFitter.Spec(symbol, columnFactory::companyName, 145.0, 360.0, flexible = true, reserveWidth = 32.0),
            TableColumnAutoFitter.Spec(signal, { WatchScorePresentation.calculate(it).label }, 76.0, 110.0),
            TableColumnAutoFitter.Spec(move, { percent(it.windowChangePercent) }, 62.0, 105.0, reserveWidth = 4.0),
            TableColumnAutoFitter.Spec(price, { "${currency.symbol}%,.2f".format(convertPrice(it.symbol, it.price)) }, 62.0, 110.0, reserveWidth = 4.0),
            TableColumnAutoFitter.Spec(scoreColumn, { SignalMetricPresentation.strength(it).label }, 68.0, 115.0),
            TableColumnAutoFitter.Spec(outcome, { SignalMetricPresentation.outcome(it).label }, 78.0, 135.0),
            TableColumnAutoFitter.Spec(priceAction, { SignalMetricPresentation.priceAction(it).label }, 72.0, 100.0),
            TableColumnAutoFitter.Spec(volume, { SignalMetricPresentation.volume(it).label }, 64.0, 125.0),
            TableColumnAutoFitter.Spec(age, { SignalAgePresentation.label(it.signalAgeMinutes) }, 64.0, 90.0),
            TableColumnAutoFitter.Spec(turnover, { compactMoney(convertPrice(it.symbol, it.sessionTurnover)) }, 88.0, 145.0, reserveWidth = 8.0),
            TableColumnAutoFitter.Spec(updated, { time.format(Instant.ofEpochMilli(it.updatedAtMillis)) }, 88.0, 125.0, reserveWidth = 8.0)
        ), columnLayout.savedWidths(), columnLayout.manuallySizedColumnIds())
        columnFactory.onContentChanged = autoFitter::request
        signal.sortType = TableColumn.SortType.DESCENDING
        table.sortOrder += signal
        table.setOnSort {
            log.debug(LogTag.UI, "tableSort(columns={})", table.sortOrder.joinToString { "${it.text}:${it.sortType}" })
        }
        table.placeholder = empty
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.fixedCellSize = -1.0
        ScannerTableInteraction.install(
            table, onOpen, { onInspect(it) }, ::copySearchKeyword, ::copyText, openStock
        )
        table.minHeight = 0.0
        table.maxHeight = Double.MAX_VALUE
        table.styleClass += "scanner-table"
        tableContainer.children.setAll(table)
        val scannerSection = VBox(5.0, header, tableContainer).apply {
            styleClass += "table-section"
            VBox.setVgrow(tableContainer, Priority.ALWAYS)
        }
        marketClosedOverlay.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.ESCAPE) {
                hideMarketClosedOverlay()
                event.consume()
            }
        }
        tableSplit.apply {
            items.setAll(scannerSection, shortMovePanel)
            orientation = javafx.geometry.Orientation.HORIZONTAL
            styleClass += "table-split-pane"
            Platform.runLater { setDividerPosition(0, initialTableDivider.coerceIn(0.45, 0.82)) }
        }
        SplitPane.setResizableWithParent(scannerSection, true)
        SplitPane.setResizableWithParent(shortMovePanel, true)
        children += tableSplit
        VBox.setVgrow(tableSplit, Priority.ALWAYS)
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
    }

    fun savedColumnLayout(): String = columnLayout.capture(autoFitter.manuallySizedColumnIds())

    fun tableDividerPosition(): Double = tableSplit.dividers.firstOrNull()?.position ?: 0.68

    private fun copySearchKeyword(result: ScanResult) {
        copyText(CompanySearchTerm.from(columnFactory.companyName(result), result.symbol))
    }

    fun update(result: ScanResult) {
        log.debug(LogTag.UI, "update(symbol={}, score={})", result.symbol, result.anomalyScore)
        if (scanning) stagedRows[result.symbol] = observationOverlay.apply(result)
    }

    fun applyPriorityResult(symbol: String, result: ScanResult?) {
        log.debug(LogTag.UI, "applyPriorityResult(symbol={}, present={})", symbol, result != null)
        refreshingStatuses.remove(symbol)
        val target = if (scanning) stagedRows else rows.associateByTo(linkedMapOf(), ScanResult::symbol)
        if (result == null) target.remove(symbol) else target[symbol] = observationOverlay.apply(result)
        if (!scanning) {
            replaceRows(target.values)
            autoFitter.request()
        }
    }

    fun setRefreshing(symbol: String, refreshing: Boolean) {
        val target = if (scanning) stagedRows else rows.associateByTo(linkedMapOf(), ScanResult::symbol)
        val current = target[symbol] ?: return
        if (refreshing) {
            refreshingStatuses.putIfAbsent(symbol, current.dataStatus)
            target[symbol] = current.copy(dataStatus = "REFRESHING")
        } else if (current.dataStatus == "REFRESHING") {
            target[symbol] = current.copy(dataStatus = refreshingStatuses.remove(symbol) ?: "CACHE")
        } else {
            refreshingStatuses.remove(symbol)
        }
        if (!scanning) replaceRows(target.values)
    }

    fun applyMarketObservation(symbol: String, price: Double, observedAtMillis: Long, source: String) {
        observationOverlay.record(symbol, price, observedAtMillis, source)
        val index = rows.indexOfFirst { it.symbol == symbol }
        if (index >= 0) {
            val current = rows[index]
            val updated = observationOverlay.apply(current)
            if (updated !== current) {
                rows[index] = updated
                autoFitter.request()
            }
        }
        stagedRows[symbol]?.let { current ->
            stagedRows[symbol] = observationOverlay.apply(current)
        }
    }

    fun showSnapshot(results: Collection<ScanResult>, resultLimit: Int) {
        replaceRows(results.sortedByDescending(ScanResult::anomalyScore).take(resultLimit))
        autoFitter.request()
    }

    fun clear() {
        log.debug(LogTag.UI, "clear()")
        rows.clear(); stagedRows.clear(); refreshingStatuses.clear(); scanning = false
        scanIndicator.clearIndicator()
        marketClosedOverlay.isVisible = false; marketClosedOverlay.isManaged = false
    }

    fun beginScan(number: Int, total: Int, symbols: List<String>) {
        if (closing) return
        log.debug(LogTag.UI, "beginScan(number={}, total={}, symbols={})", number, total, symbols.size)
        stagedRows.clear(); scanning = true
        marketClosedOverlay.isVisible = false; marketClosedOverlay.isManaged = false
        cycleStatus.styleClass.remove("market-closed")
        cycleStatus.text = "Batch $number/$total · ${symbols.size} symbols"
        cycleStatus.tooltip = Tooltip(symbols.joinToString(", "))
        scanIndicator.showScanning()
    }

    fun completeScan(resultLimit: Int = 50) {
        log.debug(LogTag.UI, "completeScan(results={})", stagedRows.size)
        replaceRows(stagedRows.values.sortedByDescending(ScanResult::anomalyScore).take(resultLimit))
        stagedRows.clear(); scanning = false
        autoFitter.request()
    }

    private fun replaceRows(replacements: Collection<ScanResult>) {
        val selectedSymbol = table.selectionModel.selectedItem?.symbol
        rows.setAll(replacements.map(observationOverlay::apply))
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
        stagedRows.clear(); scanning = false
    }

    fun showCountdown(seconds: Long) {
        log.debug(LogTag.UI, "showCountdown(seconds={})", seconds)
        scanIndicator.showCountdown(seconds)
    }

    fun showMarketClosed(
        snapshotSize: Int,
        persisted: Boolean,
        nextOpening: String,
        localZone: String,
        marketHours: List<String>,
        brokerHours: List<String>
    ) {
        if (closing) return
        marketClosedTitle.text = "ALL SELECTED MARKETS ARE CLOSED"
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

    fun showClosing() {
        closing = true
        scanIndicator.clearIndicator()
        cycleStatus.text = "Closing · waiting for current operations"
        marketClosedTitle.text = "APPLICATION IS CLOSING"
        marketClosedSubtitle.text = "Finishing current transactions and saving market data…"
        marketHoursPanel.isVisible = false
        marketHoursPanel.isManaged = false
        marketClosedFooter.isVisible = false
        marketClosedFooter.isManaged = false
        marketClosedOverlay.isVisible = true
        marketClosedOverlay.isManaged = true
        marketClosedOverlay.toFront()
    }

    private fun hideMarketClosedOverlay() {
        if (closing) return
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
        ClipboardText.copy(value)
    }

    private fun compactMoney(value: Double): String = when {
        value >= 1_000_000_000 -> "${currency.symbol}%.1fB".format(value / 1_000_000_000)
        value >= 1_000_000 -> "${currency.symbol}%.1fM".format(value / 1_000_000)
        value >= 1_000 -> "${currency.symbol}%.1fK".format(value / 1_000)
        else -> "${currency.symbol}%,.0f".format(value)
    }
}
