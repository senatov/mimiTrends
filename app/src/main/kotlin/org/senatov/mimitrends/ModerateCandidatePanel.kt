package org.senatov.mimitrends

import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import org.senatov.mimitrends.model.CompanyProfile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

internal class ModerateCandidatePanel(
    private val onOpen: (String, Long) -> Unit,
    private val loadProfile: ((String) -> CompletableFuture<CompanyProfile>)? = null
) : VBox(7.0) {
    private val names = ConcurrentHashMap<String, String>()
    private val candidates = VBox(4.0)
    private var displayed = emptyList<ShortMove>()
    private var recentSymbols = emptySet<String>()
    private val anomalySymbols = linkedSetOf<String>()
    private var stateMessage = "Building fresh market context…"

    init {
        children.setAll(
            Label("Positive watch").apply { styleClass += "positive-watch-title" },
            Label("Estimated 60–90m downside safety").apply { styleClass += "positive-watch-caption" },
            candidates
        )
        styleClass += listOf("table-section", "moderate-candidate-panel")
        minWidth = 210.0
        prefWidth = 235.0
        maxWidth = 275.0
    }

    fun show(moves: Collection<ShortMove>) {
        stateMessage = "No candidates meet the current safety and entry thresholds"
        recentSymbols = moves.take(RECENT_TABLE_LIMIT).mapTo(linkedSetOf(), ShortMove::symbol)
        displayed = ModeratePositiveCandidateSelector.select(moves.toList()).take(MAX_CANDIDATES)
        render()
        displayed.forEach(::requestName)
    }

    fun showBuildingContext(symbolCount: Int) {
        displayed = emptyList()
        stateMessage = "Building fresh context for $symbolCount symbols…"
        render()
    }

    fun setAnomalySymbols(symbols: Collection<String>) {
        anomalySymbols.clear()
        symbols.mapTo(anomalySymbols, String::uppercase)
        render()
    }

    fun setAnomalyPresent(symbol: String, present: Boolean) {
        if (present) anomalySymbols += symbol.uppercase() else anomalySymbols -= symbol.uppercase()
        render()
    }

    private fun render() {
        candidates.children.setAll(if (displayed.isEmpty()) {
            listOf(Label(stateMessage).apply {
                isWrapText = true
                styleClass += "positive-watch-empty"
            })
        } else displayed.map(::candidateRow))
    }

    private fun candidateRow(move: ShortMove): HBox {
        val company = Label(names[move.symbol] ?: move.symbol).apply {
            styleClass += "positive-watch-company"
            maxWidth = Double.MAX_VALUE
        }
        val confirmations = 1 + (if (move.symbol in recentSymbols) 1 else 0) +
            (if (move.symbol in anomalySymbols) 1 else 0)
        val confirmation = Label("$confirmations/3 confirmed").apply {
            styleClass += "positive-watch-confirmation"
        }
        val entry = Label(
            if (move.entryQualityScore >= 0) {
                "Entry ${move.entryQualityScore}% · ${move.entryQualityLabel}"
            } else {
                "Entry quality unavailable"
            }
        ).apply {
            styleClass += "positive-watch-entry-quality"
            if (move.entryQualityDetails.isNotBlank()) tooltip = Tooltip(move.entryQualityDetails)
        }
        val identity = VBox(1.0, company, confirmation, entry)
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val score = Label("${ModeratePositiveCandidateSelector.positivityPercent(move)}%").apply {
            styleClass += "positive-watch-score"
            minWidth = 54.0
            prefWidth = 54.0
            alignment = Pos.CENTER
            tooltip = Tooltip(
                "${move.safetyLabel} · confidence ${move.safetyConfidence}%\n${move.safetyDetails}\n" +
                    "Long-term context: ${move.trendLabel} (${move.trendScore ?: 0}%)\n${move.trendDetails}\n" +
                    "Current 5m move: ${"%+.2f%%".format(move.changePercent)}\n" +
                    "Safety estimate, not a profit forecast."
            )
        }
        return HBox(7.0, identity, spacer, score).apply {
            alignment = Pos.CENTER_LEFT
            styleClass += "positive-watch-row"
            setOnMouseClicked { event ->
                if (event.button == MouseButton.PRIMARY) onOpen(move.symbol, move.endedAtEpochSeconds)
            }
        }
    }

    private fun requestName(move: ShortMove) {
        if (names.putIfAbsent(move.symbol, move.symbol) != null) return
        loadProfile?.invoke(move.symbol)?.whenComplete { profile, error ->
            if (error == null && profile != null) javafx.application.Platform.runLater {
                names[move.symbol] = CompanySearchTerm.normalizeDisplay(profile.name)
                render()
            }
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 7
        const val RECENT_TABLE_LIMIT = 10
    }
}
