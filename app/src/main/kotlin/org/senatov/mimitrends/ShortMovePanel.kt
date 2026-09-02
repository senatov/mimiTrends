package org.senatov.mimitrends

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
    private val updateCaption = Label("Fresh bottom reversals + stable corridors · waiting").apply {
        styleClass += "short-move-caption"
        maxWidth = Double.MAX_VALUE
        tooltip = javafx.scene.control.Tooltip(text)
    }
    private val companyNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val search = TableSearchField.create(
        "Find move…", ::applyFilter, ::openFirstMatch, table::requestFocus,
        watchlist.search, ::pinSuggestion
    )
    private val filterCount = Label().apply {
        styleClass += "table-filter-count"
        isVisible = false
        isManaged = false
    }
    private val empty = WorkspaceEmptyState.create(
        "No actionable opportunities now",
        "Fresh confirmed bottom reversals and stable tradable corridors will appear here."
    )
    private val noMatches = WorkspaceEmptyState.create(
        "No matching movements",
        "Try another company, ticker, or direction.", "Clear search"
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
        val header = VBox(
            2.0,
            HBox(
                8.0,
                Label("Trading opportunities").apply { styleClass += "table-section-title" }, spacer, headerActions
            ).apply { alignment = Pos.CENTER_LEFT },
            updateCaption
        ).apply {
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
                        if (!empty && tableRow?.item?.pattern == ShortMovePattern.RECURRING_SHARP_JUMP) {
                            styleClass += "short-move-recurring-jump"
                        }
                    }
                }
            }
            prefWidth = 210.0; minWidth = 90.0
        }
        val opportunity = TableColumn<ShortMove, Number>("Opportunity").apply {
            id = "opportunity"
            styleClass += "status-column"
            setCellValueFactory { ReadOnlyDoubleWrapper(it.value.opportunityScore.toDouble()) }
            comparator = Comparator.comparingDouble(Number::toDouble)
            setCellFactory { OpportunityCell() }
            prefWidth = 104.0; minWidth = 82.0
        }
        val priceRange = TableColumn<ShortMove, ShortMove>("Range").apply {
            id = "price_range"
            styleClass += "numeric-column"
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            comparator = ShortMoveSort.priceRange
            isSortable = true
            setCellFactory { PriceRangeCell() }
            prefWidth = 82.0; minWidth = 62.0
        }
        val direction = TableColumn<ShortMove, ShortMove>("Setup").apply {
            id = "direction"
            styleClass += "status-column"
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            comparator = ShortMoveSort.direction
            setCellFactory { DirectionCell() }; prefWidth = 135.0
        }
        val move = TableColumn<ShortMove, Number>("Room").apply {
            id = "move"
            styleClass += "numeric-column"
            setCellValueFactory { ReadOnlyDoubleWrapper(it.value.changePercent) }
            comparator = Comparator.comparingDouble(Number::toDouble)
            setCellFactory { PercentCell() }; prefWidth = 105.0
        }
        val period = TableColumn<ShortMove, ShortMove>("Period").apply {
            id = "period"
            styleClass += "temporal-column"
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            comparator = ShortMoveSort.period
            setCellFactory { object : TableCell<ShortMove, ShortMove>() {
                override fun updateItem(item: ShortMove?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else
                        "${time.format(Instant.ofEpochSecond(item.startedAtEpochSeconds))}–${time.format(Instant.ofEpochSecond(item.endedAtEpochSeconds))}"
                }
            } }
            prefWidth = 135.0
        }
        table.columns.setAll(company, opportunity, direction, move, priceRange, period)
        listOf(
            opportunity to "Current timing relevance from 0 to 100%. This is not a probability of profit.",
            priceRange to "Current price and upper corridor target, or movement start and end prices.",
            direction to "Detected movement type and its observed direction.",
            move to "Remaining upside to the corridor target, or price change across the displayed period.",
            period to "Local start and end time of the detected movement."
        ).forEach { (column, description) -> TableColumnHelp.install(column, description) }
        columnLayout = TableColumnLayout(table, savedColumns).also(TableColumnLayout<ShortMove>::install)
        autoFitter = TableColumnAutoFitter(table, listOf(
            TableColumnAutoFitter.Spec(company, { companyNames[it.symbol] ?: it.symbol }, 80.0, 240.0),
            TableColumnAutoFitter.Spec(opportunity, { "${it.opportunityScore}%" }, 82.0, 112.0),
            TableColumnAutoFitter.Spec(priceRange, ShortMovePricePresentation::text, 62.0, 88.0),
            TableColumnAutoFitter.Spec(direction, ::directionLabel, 82.0, 155.0),
            TableColumnAutoFitter.Spec(move, { "%+.2f%%".format(it.changePercent) }, 54.0, 76.0),
            TableColumnAutoFitter.Spec(period, {
                "${time.format(Instant.ofEpochSecond(it.startedAtEpochSeconds))}–${time.format(Instant.ofEpochSecond(it.endedAtEpochSeconds))}"
            }, 82.0, 105.0)
        ), columnLayout.savedWidths(), columnLayout.manuallySizedColumnIds())
        headerActions.children += listOf(search, filterCount, columnLayout.menuButton(autoFitter::resetManualSizing))
        rows.addListener(ListChangeListener<ShortMove> { updateFilterPresentation() })
        table.placeholder = empty
        table.columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY
        table.fixedCellSize = -1.0
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
        val current = moves.asSequence().filter(ShortMove::isActionableOpportunity)
            .sortedByDescending(ShortMove::opportunityScore).take(MAX_VISIBLE_MOVES).toList()
        val displayed = eventRetainer.merge(current, nowEpochSeconds).filter(ShortMove::isActionableOpportunity)
        rows.setAll(displayed)
        selected?.let { identity ->
            sortedRows.firstOrNull { it.identity() == identity }?.let(table.selectionModel::select)
        }
        val recentCount = displayed.count(ShortMove::isRetained)
        val activeCount = displayed.size - recentCount
        updateCaption.text =
            "$activeCount active · $recentCount recent · updated ${time.format(Instant.ofEpochSecond(nowEpochSeconds))}"
        updateCaption.tooltip?.text = updateCaption.text
        displayed.forEach(::requestCompanyName)
        autoFitter.request()
    }

    internal fun savedColumnLayout(): String = columnLayout.capture(autoFitter.manuallySizedColumnIds())
    internal fun focusSearch() = search.focusField()

    private fun pinSuggestion(suggestion: TableSearchSuggestion) {
        watchlist.add(suggestion.symbol)
        search.clear()
    }

    private fun directionLabel(move: ShortMove): String = when (move.pattern) {
        ShortMovePattern.RECURRING_SHARP_JUMP -> recurringDirection(move)
        ShortMovePattern.POST_DROP_STRUGGLE -> "◆ POST-DROP"
        ShortMovePattern.CONFIRMED_EXTENDED_DROP -> "◆ CONFIRMED DROP"
        ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP -> setupLabel(move, "◆ DROP RECOVERY")
        ShortMovePattern.TRADABLE_CORRIDOR -> setupLabel(move, "▰ CORRIDOR")
        ShortMovePattern.DIRECTIONAL -> if (move.changePercent >= 0.0) "▲ UP" else "▼ DOWN"
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
                    directionLabel(move).lowercase().contains(query)
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

    private class DirectionCell : TableCell<ShortMove, ShortMove>() {
        override fun updateItem(item: ShortMove?, empty: Boolean) {
            super.updateItem(item, empty)
            val label = item?.let(::directionText)
            text = if (empty) null else label
            styleClass.removeAll("short-move-up", "short-move-down", "short-move-struggle")
            if (!empty && label != null) styleClass += when {
                label.contains("RECURRING") -> "short-move-recurring-jump"
                label.contains("POST-DROP") -> "short-move-struggle"
                label.contains("UP") -> "short-move-up"
                else -> "short-move-down"
            }
        }

        private companion object {
            fun directionText(move: ShortMove): String = when (move.pattern) {
                ShortMovePattern.RECURRING_SHARP_JUMP -> recurringDirection(move)
                ShortMovePattern.POST_DROP_STRUGGLE -> "◆ POST-DROP"
                ShortMovePattern.CONFIRMED_EXTENDED_DROP -> "◆ CONFIRMED DROP"
                ShortMovePattern.RECOVERY_AFTER_EXTENDED_DROP -> setupLabel(move, "◆ DROP RECOVERY")
                ShortMovePattern.TRADABLE_CORRIDOR -> setupLabel(move, "▰ CORRIDOR")
                ShortMovePattern.DIRECTIONAL -> if (move.changePercent >= 0.0) "▲ UP" else "▼ DOWN"
            }
        }

    }

    private class PercentCell : TableCell<ShortMove, Number>() {
        override fun updateItem(item: Number?, empty: Boolean) {
            super.updateItem(item, empty); text = if (empty || item == null) null else "%+.2f%%".format(item.toDouble())
            styleClass.removeAll("short-move-up", "short-move-down")
            if (!empty && item != null) styleClass += if (item.toDouble() >= 0.0) "short-move-up" else "short-move-down"
        }
    }

    private class OpportunityCell : TableCell<ShortMove, Number>() {
        override fun updateItem(item: Number?, empty: Boolean) {
            super.updateItem(item, empty)
            styleClass.removeAll("opportunity-high", "opportunity-good", "opportunity-wait", "opportunity-late", "opportunity-avoid")
            if (empty || item == null || item.toInt() < 0) {
                text = null; tooltip = null
                return
            }
            val value = item.toInt().coerceIn(0, 100)
            text = "$value%"
            styleClass += when {
                value >= 80 -> "opportunity-high"
                value >= 60 -> "opportunity-good"
                value >= 40 -> "opportunity-wait"
                value >= 20 -> "opportunity-late"
                else -> "opportunity-avoid"
            }
            tooltip = tableRow?.item?.let { move ->
                val retentionNote = if (move.isRetained) {
                    "Recently detected; no longer confirmed by the latest scan."
                } else null
                javafx.scene.control.Tooltip(
                    listOfNotNull(retentionNote, move.opportunityDetails.takeIf(String::isNotBlank)).joinToString("\n")
                )
            }
        }
    }

    private class PriceRangeCell : TableCell<ShortMove, ShortMove>() {
        override fun updateItem(item: ShortMove?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else ShortMovePricePresentation.text(item)
            styleClass.removeAll("short-move-up", "short-move-down")
            if (!empty && item != null) {
                styleClass += if (item.changePercent >= 0.0) "short-move-up" else "short-move-down"
            }
        }
    }

    private companion object {
        const val MAX_VISIBLE_MOVES = 10
    }
}

private fun ShortMove.identity() = symbol to endedAtEpochSeconds

private fun setupLabel(move: ShortMove, activeLabel: String): String =
    if (move.isRetained) "$activeLabel · RECENT" else activeLabel

private fun recurringDirection(move: ShortMove): String =
    if (move.changePercent >= 0.0) "⚠ RECURRING UP" else "⚠ RECURRING DOWN"
