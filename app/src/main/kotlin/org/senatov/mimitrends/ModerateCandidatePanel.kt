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

    init {
        children.setAll(
            Label("Positive watch").apply { styleClass += "positive-watch-title" },
            Label("Holding gains or rising steadily").apply { styleClass += "positive-watch-caption" },
            candidates
        )
        styleClass += listOf("table-section", "moderate-candidate-panel")
        minWidth = 210.0
        prefWidth = 235.0
        maxWidth = 275.0
    }

    fun show(moves: Collection<ShortMove>) {
        displayed = ModeratePositiveCandidateSelector.select(moves.toList()).take(MAX_CANDIDATES)
        render()
        displayed.forEach(::requestName)
    }

    private fun render() {
        candidates.children.setAll(if (displayed.isEmpty()) {
            listOf(Label("No positive candidates right now").apply { styleClass += "positive-watch-empty" })
        } else displayed.map(::candidateRow))
    }

    private fun candidateRow(move: ShortMove): HBox {
        val company = Label(names[move.symbol] ?: move.symbol).apply {
            styleClass += "positive-watch-company"
            maxWidth = Double.MAX_VALUE
        }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        val score = Label("${ModeratePositiveCandidateSelector.positivityPercent(move)}%").apply {
            styleClass += "positive-watch-score"
            minWidth = 54.0
            prefWidth = 54.0
            alignment = Pos.CENTER
            tooltip = Tooltip("Relative positive-movement score based on the current five-minute move and continuity; not a profit forecast.")
        }
        return HBox(7.0, company, spacer, score).apply {
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
    }
}
