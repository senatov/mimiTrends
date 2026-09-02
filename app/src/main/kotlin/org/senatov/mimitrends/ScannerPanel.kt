package org.senatov.mimitrends

import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.transformation.SortedList
import javafx.collections.transformation.FilteredList
import javafx.geometry.*
import javafx.scene.control.*
import javafx.scene.layout.*
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

class ScannerPanel(
    private val onOpen: (ScanResult) -> Unit,
    private val shortMovePanel: ShortMovePanel,
    savedColumns: String = "",
    initialTableDivider: Double = 0.68,
    private val loadProfile: ((String) -> CompletableFuture<CompanyProfile>)? = null,
    private val openStock: (String) -> Unit = {},
    private val onShowDetectedToday: () -> Unit = {},
    private val watchlist: InstrumentWatchlistActions = InstrumentWatchlistActions()
) : VBox(7.0) {
    internal var onInspect: (ScanResult) -> Unit = {}
    private val log = LoggerFactory.getLogger(javaClass)
    private val rows = FXCollections.observableArrayList<ScanResult>()
    private val filteredRows = FilteredList(rows)
    private val sortedRows = SortedList(filteredRows)
    private val table = TableView(sortedRows)
    private val tableContainer = StackPane()
    private var closing = false
    internal val marketClosedOverlay = MarketClosedOverlay(table::requestFocus)
    private val empty = WorkspaceEmptyState.create(
        "No additional signals yet",
        "The scanner is collecting supporting market diagnostics."
    )
    private val noMatches by lazy {
        WorkspaceEmptyState.create(
        "No matching signals",
            "Try a company name, ticker, or a broader signal term.", "Clear search"
        ) { search.clear(); table.requestFocus() }
    }
    private val cycleStatus = Label()
    private val usMarketBadge = Label("US —").apply { styleClass += "market-pulse-badge" }
    private val europeMarketBadge = Label("EU —").apply { styleClass += "market-pulse-badge" }
    private val dataPulseBadge = Label("DATA waiting").apply { styleClass += "market-pulse-badge" }
    private val scanIndicator = ScanClockIndicator()
    private val stagedRows = linkedMapOf<String, ScanResult>()
    private val refreshingStatuses = mutableMapOf<String, String>()
    private val observationOverlay = MarketObservationOverlay()
    private var scanning = false
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private var currency = DisplayCurrency.EUR
    private var convertPrice: (String, Double) -> Double = { _, value -> value }
    private val columnFactory = ScannerColumnFactory(table, loadProfile) { watchlist.contains(it) }
    private val search = TableSearchField.create(
        "Find signal…", ::applyFilter, ::openFirstMatch, table::requestFocus,
        watchlist.search, ::pinSuggestion
    )
    private val filterCount = Label().apply {
        styleClass += "table-filter-count"
        isVisible = false
        isManaged = false
    }
    private val autoFitter: TableColumnAutoFitter<ScanResult>
    private val columnLayout: TableColumnLayout<ScanResult>
    private val tableSplit = SplitPane()
    private val detectedTodayButton = Button("Detected today · 0").apply {
        styleClass += "detected-today-button"
        setOnAction { onShowDetectedToday() }
    }

    init {
        log.debug(LogTag.UI, "init()")
        val header = javafx.scene.layout.HBox(
            8.0, Label("Additional market signals").apply { styleClass += "table-section-title" },
            scanIndicator, cycleStatus.apply { styleClass += "scanner-cycle" }, usMarketBadge, europeMarketBadge, dataPulseBadge,
            javafx.scene.layout.Region().also { javafx.scene.layout.HBox.setHgrow(it, Priority.ALWAYS) },
            detectedTodayButton).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "table-section-header"
        }
        sortedRows.comparatorProperty().bind(table.comparatorProperty())
        val freshness = columnFactory.freshness()
        val symbol = columnFactory.symbol()
        val signal = columnFactory.pattern()
        val entryQuality = columnFactory.metric(
            "Entry", SignalMetricPresentation::entryQualitySeverity, SignalMetricPresentation::entryQuality
        )
        val move = columnFactory.number("10m move", ScanResult::windowChangePercent, ::percent)
        val price = columnFactory.number("Price", { convertPrice(it.symbol, it.price) }) { "${currency.symbol}%,.2f".format(it) }
        val scoreColumn = columnFactory.metric("Anomaly", ScanResult::anomalyScore, SignalMetricPresentation::strength)
        val outcome = columnFactory.metric("Outcome", SignalMetricPresentation::outcomeSeverity, SignalMetricPresentation::outcome)
        val priceAction =
            columnFactory.metric("Direction", SignalMetricPresentation::priceActionSeverity, SignalMetricPresentation::priceAction)
        val volume = columnFactory.metric("Volume", SignalMetricPresentation::volumeSeverity, SignalMetricPresentation::volume)
        val age = columnFactory.signal("Age", { SignalAgePresentation.label(it.signalAgeMinutes) }) {
            it.signalAgeMinutes.toDouble()
        }
        val turnover = columnFactory.number("Turnover", { convertPrice(it.symbol, it.sessionTurnover) }, ::compactMoney)
        val updated = columnFactory.updated { time.format(Instant.ofEpochMilli(it)) }
        listOf(scoreColumn, outcome, priceAction, volume, age, turnover).forEach { it.isVisible = false }
        listOf(freshness, symbol, signal, entryQuality, move, price, scoreColumn, outcome, priceAction, volume, age, turnover, updated)
            .zip(listOf("delay", "company", "pattern", "entry_quality", "move", "price", "anomaly", "outcome", "price_action",
                "volume", "age", "turnover", "updated"))
            .forEach { (column, id) -> column.id = id }
        listOf(
            freshness to "Age of the newest candle used by the scanner.",
            signal to "Current watch score and detected signal pattern; this is not a buy recommendation.",
            entryQuality to "Entry timing assessment based on extension, confirmation, and freshness.",
            move to "Price change measured across the latest ten-minute analysis window.",
            scoreColumn to "Composite rarity and confirmation strength of the detected anomaly.",
            outcome to "Observed continuation after comparable historical signals.",
            priceAction to "Current price direction derived from the latest market observations.",
            volume to "Reported or estimated trading activity available for this instrument.",
            age to "Elapsed time since the signal was first detected.",
            turnover to "Approximate session turnover converted to the selected display currency.",
            updated to "Time of the latest quote or result update shown in this row."
        ).forEach { (column, description) -> TableColumnHelp.install(column, description) }
        columnLayout = TableColumnLayout(table, savedColumns).also(TableColumnLayout<ScanResult>::install)
        autoFitter = TableColumnAutoFitter(table, listOf(
            TableColumnAutoFitter.Spec(freshness, { FeedFreshness.ageLabel(it.analysisUpdatedAtMillis) }, 68.0, 96.0),
            TableColumnAutoFitter.Spec(symbol, columnFactory::companyName, 145.0, 360.0, flexible = true, reserveWidth = 32.0),
            TableColumnAutoFitter.Spec(signal, { WatchScorePresentation.calculate(it).label }, 76.0, 110.0),
            TableColumnAutoFitter.Spec(entryQuality, { SignalMetricPresentation.entryQuality(it).label }, 82.0, 118.0),
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
        val headerActionIndex = header.children.lastIndex
        header.children.add(headerActionIndex, search)
        header.children.add(headerActionIndex + 1, filterCount)
        header.children.add(headerActionIndex + 2, columnLayout.menuButton(autoFitter::resetManualSizing))
        rows.addListener(ListChangeListener<ScanResult> { updateFilterPresentation() })
        columnFactory.onContentChanged = { applyFilter(); autoFitter.request() }
        signal.sortType = TableColumn.SortType.DESCENDING
        table.sortOrder += signal
        table.setOnSort {
            log.debug(
                LogTag.UI, "tableSort(columns={})",
                table.sortOrder.joinToString { "${TableColumnHelp.title(it)}:${it.sortType}" })
        }
        table.placeholder = empty
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.fixedCellSize = -1.0
        ScannerTableInteraction.install(
            table, onOpen, { onInspect(it) }, ::copySearchKeyword, ::copyText, openStock, search::clear,
            { watchlist.contains(it) }, ::removePinned
        )
        table.minHeight = 0.0
        table.maxHeight = Double.MAX_VALUE
        table.styleClass += "scanner-table"
        tableContainer.children.setAll(table)
        val scannerSection = VBox(5.0, header, tableContainer).apply {
            styleClass += "table-section"
            VBox.setVgrow(tableContainer, Priority.ALWAYS)
        }
        tableSplit.apply {
            items.setAll(scannerSection, shortMovePanel)
            orientation = javafx.geometry.Orientation.HORIZONTAL
            styleClass += "table-split-pane"
            Platform.runLater { setDividerPosition(0, initialTableDivider.coerceIn(0.45, 0.82)) }
        }
        SplitPaneReset.install(tableSplit, initialTableDivider.coerceIn(0.45, 0.82))
        SplitPane.setResizableWithParent(scannerSection, true)
        SplitPane.setResizableWithParent(shortMovePanel, true)
        children += tableSplit
        VBox.setVgrow(tableSplit, Priority.ALWAYS)
        minHeight = 0.0
        maxHeight = Double.MAX_VALUE
    }

    private fun pinSuggestion(suggestion: TableSearchSuggestion) {
        watchlist.add(suggestion.symbol)
        search.clear()
    }

    private fun removePinned(symbol: String) {
        watchlist.remove(symbol)
        table.refresh()
    }
    fun savedColumnLayout(): String = columnLayout.capture(autoFitter.manuallySizedColumnIds())
    fun tableDividerPosition(): Double = tableSplit.dividers.firstOrNull()?.position ?: 0.68
    internal fun focusSignalSearch() = search.focusField()
    internal fun focusMoveSearch() = shortMovePanel.focusSearch()
    fun setDetectedTodayCount(count: Int) {
        detectedTodayButton.text = "Detected today · $count"
    }
    private fun copySearchKeyword(result: ScanResult) {
        copyText(CompanySearchTerm.from(columnFactory.companyName(result), result.symbol))
    }

    private fun applyFilter() {
        val query = search.text.trim().lowercase()
        filteredRows.setPredicate { result ->
            query.isBlank() || result.symbol.lowercase().contains(query) ||
                    columnFactory.companyName(result).lowercase().contains(query) ||
                    result.signalSource.lowercase().contains(query)
        }
        updateFilterPresentation()
    }
    private fun updateFilterPresentation() {
        val filtering = search.text.isNotBlank()
        table.placeholder = if (filtering) noMatches else empty
        filterCount.text = "${filteredRows.size}/${rows.size}"
        filterCount.isVisible = filtering
        filterCount.isManaged = filtering
    }
    private fun openFirstMatch() {
        sortedRows.firstOrNull()?.let { first ->
            table.selectionModel.select(first)
            table.scrollTo(first)
            table.requestFocus()
            onOpen(first)
        }
    }
    fun update(result: ScanResult) {
        log.debug(LogTag.UI, "update(symbol={}, score={})", result.symbol, result.anomalyScore)
        if (scanning) stagedRows[result.symbol] = observationOverlay.apply(result)
    }

    fun applyPriorityResult(symbol: String, result: ScanResult?) {
        log.debug(LogTag.UI, "applyPriorityResult(symbol={}, present={})", symbol, result != null)
        refreshingStatuses.remove(symbol)
        if (scanning) {
            if (result == null) stagedRows.remove(symbol) else stagedRows[symbol] = observationOverlay.apply(result)
            return
        }
        val index = rows.indexOfFirst { it.symbol == symbol }
        if (result == null && index >= 0) rows.removeAt(index)
        else if (result != null && index >= 0) rows[index] = observationOverlay.apply(result)
        else if (result != null) rows += observationOverlay.apply(result)
        autoFitter.request()
    }

    fun setRefreshing(symbol: String, refreshing: Boolean) {
        val current = (if (scanning) stagedRows[symbol] else rows.firstOrNull { it.symbol == symbol }) ?: return
        val updated: ScanResult
        if (refreshing) {
            refreshingStatuses.putIfAbsent(symbol, current.dataStatus)
            updated = current.copy(dataStatus = "REFRESHING")
        } else if (current.dataStatus == "REFRESHING") {
            updated = current.copy(dataStatus = refreshingStatuses.remove(symbol) ?: "CACHE")
        } else {
            refreshingStatuses.remove(symbol)
            return
        }
        if (scanning) stagedRows[symbol] = updated else rows[rows.indexOf(current)] = updated
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
        marketClosedOverlay.hide()
    }

    fun beginScan(number: Int, total: Int, symbols: List<String>) {
        if (closing) return
        log.debug(LogTag.UI, "beginScan(number={}, total={}, symbols={})", number, total, symbols.size)
        stagedRows.clear(); scanning = true
        marketClosedOverlay.hide()
        cycleStatus.styleClass.remove("market-closed")
        cycleStatus.text = "Batch $number/$total · ${symbols.size} symbols"
        cycleStatus.tooltip = Tooltip(symbols.joinToString(", "))
        scanIndicator.showScanning()
        val us = symbols.count { !it.contains('.') }
        val europe = symbols.size - us
        usMarketBadge.text = "US $us"
        europeMarketBadge.text = "EU $europe"
        dataPulseBadge.text = "DATA scanning"
        dataPulseBadge.styleClass.removeAll("market-pulse-live", "market-pulse-warning")
        dataPulseBadge.styleClass += "market-pulse-warning"
    }

    fun completeScan(resultLimit: Int = 50) {
        log.debug(LogTag.UI, "completeScan(results={})", stagedRows.size)
        val ordered = stagedRows.values.sortedByDescending(ScanResult::anomalyScore)
        val visible = (ordered.take(resultLimit) + ordered.filter { watchlist.contains(it.symbol) })
            .distinctBy(ScanResult::symbol)
        replaceRows(visible)
        stagedRows.clear(); scanning = false
        val freshest = rows.minOfOrNull { FeedFreshness.ageMinutes(it.analysisUpdatedAtMillis) }
        dataPulseBadge.text = freshest?.let { "DATA ${it}m" } ?: "DATA no signals"
        dataPulseBadge.styleClass.removeAll("market-pulse-live", "market-pulse-warning")
        dataPulseBadge.styleClass += if (freshest != null && freshest <= 3) "market-pulse-live" else "market-pulse-warning"
        autoFitter.request()
    }

    private fun replaceRows(replacements: Collection<ScanResult>) {
        val selectedSymbol = table.selectionModel.selectedItem?.symbol
        rows.setAll(replacements.map(observationOverlay::apply))
        val retainedIndex = selectedSymbol?.let { symbol -> sortedRows.indexOfFirst { it.symbol == symbol } } ?: -1
        if (retainedIndex >= 0) {
            table.selectionModel.select(retainedIndex)
            return
        }
        if (selectedSymbol != null) {
            table.selectionModel.clearSelection()
            log.debug(LogTag.UI, "selectionCleared(previous={} reason=not-present)", selectedSymbol)
            return
        }
        sortedRows.firstOrNull()?.let { first ->
            table.selectionModel.select(0)
            table.scrollTo(0)
            log.debug(LogTag.UI, "initialSelection(symbol={})", first.symbol)
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
        cycleStatus.styleClass.remove("market-closed")
        cycleStatus.styleClass += "market-closed"
        cycleStatus.text = "ALL SELECTED MARKETS ARE CLOSED · " + when {
            snapshotSize == 0 -> "no saved results"
            persisted -> "$snapshotSize saved results · NOT LIVE"
            else -> "$snapshotSize cached close results · NOT LIVE"
        }
        cycleStatus.tooltip = Tooltip("The scanner is not presenting cached closing bars as current market signals.")
        marketClosedOverlay.showSnapshot(nextOpening, localZone, marketHours, brokerHours)
    }

    fun showClosing() {
        closing = true
        scanIndicator.clearIndicator()
        cycleStatus.text = "Closing · waiting for current operations"
        marketClosedOverlay.showClosing()
    }

    fun setCurrency(value: DisplayCurrency, converter: (String, Double) -> Double) {
        log.debug(LogTag.UI, "setCurrency(currency={})", value)
        currency = value; convertPrice = converter; table.refresh(); autoFitter.request()
    }

    fun setAppearance(value: TableAppearance) {
        log.debug(LogTag.UI, "setAppearance(font={}, size={})", value.fontFamily, value.fontSize)
        ScannerTableAppearance.apply(table, value)
        autoFitter.request()
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