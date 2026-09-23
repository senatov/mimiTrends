package org.senatov.mimitrends.shortmove

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import javafx.beans.property.ReadOnlyDoubleWrapper
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.collections.transformation.SortedList
import javafx.collections.transformation.FilteredList
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.senatov.mimitrends.model.CompanyProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ShortMovePanel(
    private val onOpen: (String, Long) -> Unit,
    savedColumns: String = "",
    private val loadProfile: ((String) -> java.util.concurrent.CompletableFuture<CompanyProfile>)? = null,
    private val copyText: (String) -> Unit = {},
    private val openExternalChart: (String) -> Unit = {},
    private val watchlist: InstrumentWatchlistActions = InstrumentWatchlistActions()
) : VBox(5.0) {
    private val rows = FXCollections.observableArrayList<ShortMove>()
    private val filteredRows = FilteredList(rows)
    private val sortedRows = SortedList(filteredRows)
    private val table = TableView(sortedRows)
    private val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    private val updateCaption = Label("Waiting for the first focused scan").apply {
        styleClass += "short-move-caption"
        maxWidth = Double.MAX_VALUE
        tooltip = javafx.scene.control.Tooltip(text)
    }
    private val scanCaption = Label("Scan waiting").apply { styleClass += "short-move-caption" }
    private val companyNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val search = TableSearchField.create(
        "Find alert…", ::applyFilter, ::openFirstMatch, table::requestFocus,
        watchlist.search, ::pinSuggestion
    )
    private val filterCount = Label().apply {
        styleClass += "table-filter-count"
        isVisible = false
        isManaged = false
    }
    private val empty = WorkspaceEmptyState.create(
        "No live corridor or crash alerts",
        "Stable two-hour corridors and confirmed four-minute rapid crashes will appear here."
    )
    private val noMatches = WorkspaceEmptyState.create(
        "No matching movements",
        "Try another company, ticker, or event type.", "Clear search"
    ) { search.clear(); table.requestFocus() }
    private val eventRetainer = ShortMoveEventRetainer()
    private val columnLayout: TableColumnLayout<ShortMove>
    private val autoFitter: TableColumnAutoFitter<ShortMove>
    private val profileUpdates by lazy {
        UiUpdateBatcher<String, CompanyProfile>({ task -> javafx.application.Platform.runLater(task) }) { profiles ->
            profiles.forEach { companyNames[it.symbol] = CompanySearchTerm.normalizeDisplay(it.name) }
            applyFilter()
            table.refresh()
            if (table.sortOrder.any { it.id == "company" }) table.sort()
            autoFitter.request()
        }
    }

    init {
        sortedRows.comparatorProperty().bind(table.comparatorProperty())
        val spacer = javafx.scene.layout.Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val headerActions = HBox(7.0)
        val header = HBox(
            8.0,
            Label("Live radar").apply { styleClass += "table-section-title" },
            updateCaption, scanCaption, spacer, headerActions
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += listOf("table-section-header", "short-move-header")
        }
        val company = TableColumn<ShortMove, String>("Company").apply {
            id = "company"
            styleClass += "company-column"
            setCellValueFactory { ReadOnlyStringWrapper(companyNames[it.value.symbol] ?: it.value.symbol) }
            comparator = Comparator { left, right -> left.compareTo(right, ignoreCase = true) }
            setCellFactory {
                object : TableCell<ShortMove, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = null
                        graphic = if (empty || item == null || tableRow?.item == null) null else
                            ShortMoveCompanyGraphic.create(tableRow.item, item, watchlist)
                        styleClass.remove("short-move-recurring-jump")
                        if (!empty && tableRow?.item?.pattern == ShortMovePattern.RAPID_CRASH) {
                            styleClass += "short-move-recurring-jump"
                        }
                    }
                }
            }
            prefWidth = 210.0; minWidth = 90.0
        }
        val direction = TableColumn<ShortMove, ShortMove>("Event").apply {
            id = "direction"
            styleClass += "status-column"
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            comparator = ShortMoveSort.direction
            setCellFactory { ShortMoveDirectionCell() }; prefWidth = 155.0
        }
        val movement = TableColumn<ShortMove, ShortMove>("Movement").apply {
            id = "movement"
            styleClass += "numeric-column"
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            comparator = Comparator.comparingDouble { move: ShortMove -> move.changePercent }
            setCellFactory { ShortMoveMovementCell() }; prefWidth = 170.0
        }
        val price = TableColumn<ShortMove, Number>("Price").apply {
            id = "price"
            styleClass += "numeric-column"
            setCellValueFactory { ReadOnlyDoubleWrapper(ShortMovePresentation.currentPrice(it.value)) }
            comparator = Comparator.comparingDouble(Number::toDouble)
            setCellFactory { ShortMoveCurrentPriceCell() }
            prefWidth = 100.0
        }
        val age = TableColumn<ShortMove, ShortMove>("Age").apply {
            id = "age"
            styleClass += "temporal-column"
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            comparator = Comparator.comparingLong(ShortMove::eventEpochSeconds)
            setCellFactory { ShortMoveAgeCell() }
            prefWidth = 72.0
        }
        table.columns.setAll(company, direction, movement, price, age)
        listOf(
            direction to "Confirmed live event type. RECENT means it is retained but no longer confirmed.",
            movement to "Four-minute decline for a crash, or corridor width and remaining room for a corridor.",
            price to "Latest observed price used by the focused radar.",
            age to "Time since the latest confirmed event observation."
        ).forEach { (column, description) -> TableColumnHelp.install(column, description) }
        columnLayout = TableColumnLayout(table, savedColumns).also(TableColumnLayout<ShortMove>::install)
        autoFitter = TableColumnAutoFitter(
            table, listOf(
                TableColumnAutoFitter.Spec(company, { companyNames[it.symbol] ?: it.symbol }, 80.0, 240.0),
                TableColumnAutoFitter.Spec(direction, ::shortMoveDirectionLabel, 112.0, 180.0),
                TableColumnAutoFitter.Spec(movement, ShortMovePresentation::movement, 130.0, 210.0),
                TableColumnAutoFitter.Spec(price, { "%,.2f".format(ShortMovePresentation.currentPrice(it)) }, 70.0, 110.0),
                TableColumnAutoFitter.Spec(age, { ShortMovePresentation.age(it, Instant.now().epochSecond) }, 54.0, 82.0)
            ), columnLayout.savedWidths(), columnLayout.manuallySizedColumnIds()
        )
        headerActions.children += listOf(search, filterCount)
        columnLayout.onReset = autoFitter::resetManualSizing
        rows.addListener(ListChangeListener<ShortMove> { updateFilterPresentation() })
        table.placeholder = empty
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.fixedCellSize = 30.0
        VBox.setVgrow(table, Priority.ALWAYS)
        table.styleClass += listOf("scanner-table", "short-move-table")
        table.setRowFactory {
            object : TableRow<ShortMove>() {
                var contextItem: ShortMove? = null
                val removeItem = MenuItem("Remove from watchlist").apply {
                    setOnAction { contextItem?.symbol?.let(watchlist.remove) }
                }

                init {
                    setOnMouseClicked { event ->
                        if (!isEmpty && event.button == MouseButton.PRIMARY && event.clickCount == 1) {
                            onOpen(item.symbol, item.endedAtEpochSeconds)
                        }
                    }
                    contextMenu = ContextMenu(
                        MenuItem("Copy search keyword").apply {
                            accelerator = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
                            setOnAction { contextItem?.let { move -> copyText(searchKeyword(move)) } }
                        },
                        MenuItem("Copy ticker").apply { setOnAction { contextItem?.symbol?.let(copyText) } },
                        MenuItem("Open Stock").apply {
                            accelerator = KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN)
                            setOnAction { contextItem?.symbol?.let(openExternalChart) }
                        },
                        removeItem
                    ).apply {
                        setOnShowing {
                            contextItem = item.takeUnless { isEmpty }
                            removeItem.isVisible = contextItem?.symbol?.let(watchlist.contains) == true
                            contextItem?.let { table.selectionModel.select(it) }
                        }
                        setOnHidden { contextItem = null }
                    }
                }

                override fun updateItem(item: ShortMove?, empty: Boolean) {
                    super.updateItem(item, empty)
                    styleClass.remove("user-watchlist-row")
                    if (!empty && item != null && watchlist.contains(item.symbol)) styleClass += "user-watchlist-row"
                    tooltip = if (!empty && item?.isRetained == true) javafx.scene.control.Tooltip(
                        "Recently detected · no longer confirmed by the latest scan"
                    ) else null
                }
            }
        }
        table.setOnKeyPressed { event ->
            val selected = table.selectionModel.selectedItem
            when {
                event.code == KeyCode.ENTER -> selected?.let { onOpen(it.symbol, it.endedAtEpochSeconds) }
                event.code == KeyCode.O && event.isShortcutDown -> selected?.symbol?.let(openExternalChart)
                event.code == KeyCode.C && event.isShortcutDown -> selected?.let { copyText(searchKeyword(it)) }
                event.code == KeyCode.ESCAPE && search.clear() -> Unit
                else -> return@setOnKeyPressed
            }
            event.consume()
        }
        styleClass += "table-section"
        children.setAll(header, table)
    }

    internal fun show(moves: Collection<ShortMove>, nowEpochSeconds: Long = Instant.now().epochSecond) {
        val selected = table.selectionModel.selectedItem?.identity()
        val current = moves.asSequence().filter { move ->
            move.isActionableOpportunity() || move.pattern == ShortMovePattern.RAPID_CRASH
        }.sortedWith(
            compareBy<ShortMove>(::shortMoveAlertPriority).thenByDescending(ShortMove::opportunityScore)
        ).take(MAX_VISIBLE_MOVES).toList()
        val displayed = eventRetainer.merge(current, nowEpochSeconds).filter { move ->
            move.isActionableOpportunity() || move.pattern == ShortMovePattern.RAPID_CRASH
        }
        rows.setAll(displayed)
        selected?.let { identity ->
            sortedRows.firstOrNull { it.identity() == identity }?.let(table.selectionModel::select)
        }
        val recentCount = displayed.count(ShortMove::isRetained)
        val activeCount = displayed.size - recentCount
        updateCaption.text =
            "$activeCount live · $recentCount recent · updated ${time.format(Instant.ofEpochSecond(nowEpochSeconds))}"
        updateCaption.tooltip?.text = updateCaption.text
        displayed.forEach(::requestCompanyName)
        autoFitter.request()
    }

    internal fun savedColumnLayout(): String = columnLayout.capture(autoFitter.manuallySizedColumnIds())
    internal fun focusSearch() = search.focusField()

    internal fun showScanProgress(completed: Int, cycleSize: Int, poolSize: Int) {
        scanCaption.text = "Scan $completed/$cycleSize · pool $poolSize"
    }

    private fun pinSuggestion(suggestion: TableSearchSuggestion) {
        watchlist.add(suggestion.symbol)
        search.clear()
    }

    private fun requestCompanyName(move: ShortMove) {
        if (companyNames.containsKey(move.symbol)) return
        companyNames[move.symbol] = move.symbol
        loadProfile?.invoke(move.symbol)?.whenComplete { profile, error ->
            if (error == null && profile != null) profileUpdates.offer(move.symbol, profile)
        }
    }

    private fun searchKeyword(move: ShortMove): String =
        CompanySearchTerm.from(companyNames[move.symbol] ?: move.symbol, move.symbol)

    private fun applyFilter() {
        val query = search.text.trim().lowercase()
        filteredRows.setPredicate { move ->
            query.isBlank() || move.symbol.lowercase().contains(query) ||
                    (companyNames[move.symbol] ?: move.symbol).lowercase().contains(query) ||
                    shortMoveDirectionLabel(move).lowercase().contains(query)
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
            onOpen(first.symbol, first.endedAtEpochSeconds)
        }
    }

    private companion object {
        const val MAX_VISIBLE_MOVES = 10
    }
}

private fun ShortMove.identity() = symbol to endedAtEpochSeconds
