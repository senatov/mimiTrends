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
    private val loadProfile: ((String) -> CompletableFuture<CompanyProfile>)?,
    private val isPinned: (String) -> Boolean = { false }
) {
    private val logoImages = ConcurrentHashMap<String, Image>()
    private val companyNames = ConcurrentHashMap<String, String>()
    private val companyProfiles = ConcurrentHashMap<String, CompanyProfile>()
    private val profileRequestTimes = ConcurrentHashMap<String, Long>()
    var onContentChanged: () -> Unit = {}
    private val profileUpdates by lazy {
        UiUpdateBatcher<String, CompanyProfile>({ task -> Platform.runLater(task) }) { profiles ->
            profiles.forEach { profile ->
                companyProfiles[profile.symbol] = profile
                profile.name.takeUnless { it == "Company unavailable" }?.let { companyNames[profile.symbol] = it }
            }
            table.refresh()
            if (table.sortOrder.any { it.id == "company" }) table.sort()
            onContentChanged()
        }
    }

    fun companyName(result: ScanResult): String = companyNames[result.symbol] ?: result.symbol

    fun freshness(): TableColumn<ScanResult, Number> = TableColumn<ScanResult, Number>("Delay").apply {
        styleClass += "temporal-column"
        setCellValueFactory { ReadOnlyLongWrapper(FeedFreshness.ageMinutes(it.value.analysisUpdatedAtMillis)) }
        comparator = Comparator { left, right -> left.toLong().compareTo(right.toLong()) }
        isSortable = true
        setCellFactory {
            object : TableCell<ScanResult, Number>() {
                override fun updateItem(item: Number?, empty: Boolean) {
                    super.updateItem(item, empty)
                    val result = tableRow?.item
                    if (empty || item == null || result == null) {
                        text = null; tooltip = null; styleClass.remove("stale-feed-cell")
                        return
                    }
                    val now = System.currentTimeMillis()
                    val stale = FeedFreshness.isStale(result.analysisUpdatedAtMillis, result.dataStatus, now)
                    text = if (result.dataStatus == FeedFreshness.REFRESHING) "⌛ refreshing"
                    else "${FeedFreshness.icon(result.analysisUpdatedAtMillis, result.dataStatus, now)} " +
                        FeedFreshness.ageLabel(result.analysisUpdatedAtMillis, now)
                    tooltip = Tooltip(
                        FeedFreshness.tooltip(result.analysisUpdatedAtMillis, result.dataStatus, now) +
                            "\n${FeedFreshness.timeline(result, now)}" +
                            "\nAge of the latest candle used for analysis; Updated shows the latest displayed quote."
                    )
                    styleClass.remove("stale-feed-cell")
                    if (stale) styleClass += "stale-feed-cell"
                }
            }
        }
        configure(78.0, 68.0)
    }

    fun signal(
        title: String,
        value: (ScanResult) -> String,
        sortValue: (ScanResult) -> Double
    ): TableColumn<ScanResult, ScanResult> =
        TableColumn<ScanResult, ScanResult>(title).apply {
            styleClass += "status-column"
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            comparator = Comparator { left, right -> sortValue(left).compareTo(sortValue(right)) }
            setCellFactory {
                object : TableCell<ScanResult, ScanResult>() {
                    override fun updateItem(item: ScanResult?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) {
                            text = null; style = ""; tooltip = null
                            return
                        }
                        val visual = signalVisual(item)
                        text = value(item)
                        style = "-fx-text-fill: ${visual.color};"
                        tooltip = Tooltip(visual.description).apply { showDelay = Duration.millis(450.0) }
                    }
                }
            }
            configure(115.0, 55.0)
        }

    fun pattern(): TableColumn<ScanResult, ScanResult> = TableColumn<ScanResult, ScanResult>("Opportunity").apply {
        styleClass += "status-column"
        setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
        comparator = Comparator { left, right ->
            WatchScorePresentation.calculate(left).value.compareTo(WatchScorePresentation.calculate(right).value)
        }
        setCellFactory {
            object : TableCell<ScanResult, ScanResult>() {
                override fun updateItem(item: ScanResult?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null; graphic = null; tooltip = null
                        return
                    }
                    val content = SignalPatternText.parse(item.signalSource)
                    val watchScore = WatchScorePresentation.calculate(item)
                    graphic = Label(watchScore.label).apply {
                        styleClass += "pattern-watch-score"
                        style = "-fx-text-fill: ${watchScore.color}; " +
                                "-fx-background-color: ${opportunityBackground(watchScore.value)}; " +
                                "-fx-background-radius: 999px; -fx-padding: 3px 8px; -fx-font-weight: 700;"
                    }
                    text = null
                    tooltip = Tooltip("Detected: ${content.primary}\n${content.qualifiers.orEmpty()}\n${watchScore.details}").apply {
                        showDelay = Duration.millis(450.0)
                    }
                }
            }
        }
        configure(145.0, 90.0)
    }

    fun metric(
        title: String,
        sortValue: (ScanResult) -> Double,
        metric: (ScanResult) -> SignalMetric
    ): TableColumn<ScanResult, ScanResult> = TableColumn<ScanResult, ScanResult>(title).apply {
        styleClass += "status-column"
        setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
        comparator = Comparator { left, right -> sortValue(left).compareTo(sortValue(right)) }
        isSortable = true
        setCellFactory {
            object : TableCell<ScanResult, ScanResult>() {
                override fun updateItem(item: ScanResult?, empty: Boolean) {
                    super.updateItem(item, empty)
                    styleClass.remove(RARE_IMPULSE_STYLE_CLASS)
                    if (empty || item == null) {
                        text = null; style = ""; tooltip = null
                        return
                    }
                    val presentation = metric(item)
                    text = presentation.label
                    if (presentation.label.startsWith("Rare impulse", ignoreCase = true)) {
                        style = ""
                        styleClass += RARE_IMPULSE_STYLE_CLASS
                    } else {
                        style = "-fx-text-fill: ${presentation.color};"
                    }
                    tooltip = Tooltip(presentation.details).apply { showDelay = Duration.millis(350.0) }
                }
            }
        }
            configure(110.0, 55.0)
    }

    fun number(
        title: String,
        value: (ScanResult) -> Double,
        format: (Double) -> String
    ): TableColumn<ScanResult, Number> = TableColumn<ScanResult, Number>(title).apply {
        styleClass += "numeric-column"
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
            styleClass += "temporal-column"
            setCellValueFactory { ReadOnlyLongWrapper(it.value.updatedAtMillis) }
            comparator = Comparator { left, right -> left.toLong().compareTo(right.toLong()) }
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

    fun symbol(): TableColumn<ScanResult, String> = TableColumn<ScanResult, String>("Company").apply {
        styleClass += "company-column"
        setCellValueFactory { ReadOnlyObjectWrapper(it.value.symbol) }
        comparator = Comparator { left, right ->
            (companyNames[left] ?: left).compareTo(companyNames[right] ?: right, ignoreCase = true)
        }
        setCellFactory {
            object : TableCell<ScanResult, String>() {
                override fun updateItem(symbol: String?, empty: Boolean) {
                    super.updateItem(symbol, empty)
                    if (empty || symbol == null) {
                        text = null; graphic = null; tooltip = null
                        return
                    }
                    text = null
                    contentDisplay = ContentDisplay.LEFT
                    val cachedProfile = companyProfiles[symbol]
                    val cachedName = cachedProfile?.name ?: "Loading company…"
                    graphic = companyGraphic(symbol, cachedName, cachedProfile?.logoBytes, tableRow.item)
                    tooltip = companyTooltip(symbol, cachedProfile)
                    val now = System.currentTimeMillis()
                    val lastRequest = profileRequestTimes[symbol] ?: 0L
                    if (cachedProfile?.logoBytes != null || now - lastRequest < PROFILE_RETRY_MILLIS) return
                    profileRequestTimes[symbol] = now
                    loadProfile?.invoke(symbol)?.whenComplete(
                        BiConsumer<CompanyProfile?, Throwable?> { profile, error ->
                            if (error == null && profile != null) {
                                val displayName = CompanySearchTerm.displayNameOrNull(profile.name, symbol)
                                    ?: "Company unavailable"
                                profileUpdates.offer(symbol, profile.copy(name = displayName))
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

    private fun opportunityBackground(value: Int): String = when {
        value >= 80 -> "rgba(181,232,204,0.82)"
        value >= 60 -> "rgba(210,239,221,0.82)"
        value >= 40 -> "rgba(250,232,157,0.80)"
        value >= 20 -> "rgba(249,213,174,0.82)"
        else -> "rgba(244,199,204,0.82)"
    }

    private fun signalVisual(result: ScanResult): SignalVisual {
        val down = result.signalSource.contains('↓')
        val directionalColor = if (down) "#a61f2d" else "#087443"
        return when {
            result.signalSource.contains("· cooling") -> SignalVisual(
                TABLE_TEXT_COLOR, 400,
                "Recent event · no longer qualifies as an active signal · retained briefly for context"
            )
            result.signalSource.contains("wait for pullback") -> SignalVisual(
                "#9a6717", 600,
                "Extended rise · entry timing is unfavorable · wait for consolidation or a controlled pullback"
            )
            result.signalAgeMinutes > 0 -> SignalVisual(TABLE_TEXT_COLOR, 400, "Old signal · ${result.signalAgeMinutes} minute(s) ago")
            result.signalSource.contains("relaxed", true) -> SignalVisual("#8a5600", 500, "Questionable signal · accepted only by relaxed statistical thresholds")
            result.signalSource.startsWith("Recovery breakout") ->
                SignalVisual("#087443", 600, "Fresh breakout after recovery consolidation · earlier decline remains a risk factor")
            result.signalSource.startsWith("Recovery rise") ->
                SignalVisual("#087443", 600, "Continuing recovery rise · earlier decline remains a risk factor")
            result.signalSource.startsWith("Steady rise") &&
                kotlin.math.abs(result.windowChangePercent) < 0.90 ->
                SignalVisual(STEADY_RISE_COLOR, 500, "Weak current trend · direction is active but close to the minimum threshold")
            result.signalSource.startsWith("Steady rise") ->
                SignalVisual(STEADY_RISE_COLOR, 600, "Rising pattern across the measured window · continuation is not implied")
            result.signalSource.startsWith("Impulse") ->
                SignalVisual(IMPULSE_COLOR, 600, "Fresh price impulse · direction is descriptive, not a forecast")
            result.signalSource.startsWith("Trend") && kotlin.math.abs(result.windowChangePercent) < 0.90 ->
                SignalVisual(directionalColor, 500, "Weak current trend · direction is active but close to the minimum threshold")
            result.signalSource.startsWith("Trend") ->
                SignalVisual(directionalColor, 500, "Directional pattern across the measured window · continuation is not implied")
            result.anomalyScore < 1.25 -> SignalVisual("#8a5600", 500, "Questionable signal · low composite confidence")
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
        val colors = CompanyBadgePalette.forSymbol(symbol)
        val placeholder = Label(symbol.take(1)).apply {
            minWidth = size; prefWidth = size; maxWidth = size
            minHeight = size; prefHeight = size; maxHeight = size
            alignment = Pos.CENTER
            style = "-fx-background-color: ${colors.background}; -fx-background-radius: ${size / 2}; " +
                    "-fx-text-fill: ${colors.foreground}; -fx-font-weight: 600;"
        }
        return StackPane(placeholder).apply {
            minWidth = size; prefWidth = size; maxWidth = size
            minHeight = size; prefHeight = size; maxHeight = size
            bytes?.let { logoBytes ->
                val image = logoImages.computeIfAbsent(symbol) { Image(ByteArrayInputStream(logoBytes)) }
                if (!image.isError) children += ImageView(image).apply {
                    fitWidth = size; fitHeight = size; isPreserveRatio = true; isSmooth = true
                    styleClass += "company-logo"
                }
            }
        }
    }

    private fun companyGraphic(symbol: String, name: String, logo: ByteArray?, result: ScanResult?): HBox {
        val content = HBox(7.0).apply { alignment = Pos.CENTER_LEFT }
        content.children += logoBadge(symbol, logo, 22.0)
        if (result?.repeatingCycleStrength?.isFinite() == true) {
            content.children += Label(REPEATING_CYCLE_GLYPH).apply {
                style = "-fx-text-fill: #ef233c; -fx-font-size: 16px; -fx-font-weight: 700;"
                tooltip = Tooltip(
                    "Repeating 2–3 minute price cycle · statistical strength " +
                        "${"%.0f".format(result.repeatingCycleStrength * 100.0)}%"
                )
            }
        }
        content.children += Label(name)
        val venue = result?.let { MarketVenuePresentation.forInstrument(symbol, it.dataStatus) }
        if (venue != null) content.children += MarketVenueFlag.create(venue)
        if (isPinned(symbol)) content.children += Label("◆").apply {
            styleClass += "watchlist-marker"
            tooltip = Tooltip("Pinned to your persistent watchlist")
        }
        return content
    }

    private data class SignalVisual(val color: String, val weight: Int, val description: String)

    private companion object {
        const val RARE_IMPULSE_STYLE_CLASS = "rare-impulse-cell"
        const val TABLE_TEXT_COLOR = "#1f2933"
        const val STEADY_RISE_COLOR = "#0B5D3B"
        const val IMPULSE_COLOR = "#4B1F6F"
        const val REPEATING_CYCLE_GLYPH = "↻"
        const val PROFILE_RETRY_MILLIS = 2_000L
    }
}
