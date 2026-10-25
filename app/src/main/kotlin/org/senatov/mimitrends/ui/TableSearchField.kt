package org.senatov.mimitrends.ui

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import javafx.scene.control.TextField
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.animation.PauseTransition
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
import javafx.util.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

data class TableSearchSuggestion(val symbol: String, val name: String, val exchange: String)

internal object TableSearchField {
    fun create(
        prompt: String,
        onChanged: () -> Unit,
        onSubmit: () -> Unit = {},
        onEscape: () -> Unit = {},
        suggestions: ((String) -> CompletableFuture<List<TableSearchSuggestion>>)? = null,
        onSuggestionSelected: (TableSearchSuggestion) -> Unit = {}
    ): TableSearchControl = TableSearchControl(
        prompt, onChanged, onSubmit, onEscape, suggestions, onSuggestionSelected
    )
}

internal class TableSearchControl(
    prompt: String,
    onChanged: () -> Unit,
    onSubmit: () -> Unit,
    onEscape: () -> Unit,
    private val suggestions: ((String) -> CompletableFuture<List<TableSearchSuggestion>>)?,
    private val onSuggestionSelected: (TableSearchSuggestion) -> Unit
) : HBox() {
    private val input = TextField()
    private val clear = Button("×")
    private val suggestionMenu = ContextMenu()
    private val suggestionGeneration = AtomicLong()
    private val suggestionDelay = PauseTransition(Duration.millis(300.0))
    val text: String get() = input.text.orEmpty()

    init {
        styleClass += "table-search-control"
        input.apply {
            promptText = prompt
            accessibleText = prompt
            accessibleHelp = "Type to filter, then press Enter to open the first result"
            styleClass += "table-search-field"
            prefWidth = 135.0
            minWidth = 90.0
            textProperty().addListener { _, _, value ->
                clear.isVisible = value.isNotEmpty()
                clear.isManaged = clear.isVisible
                onChanged()
                updateSuggestions(value)
            }
            setOnKeyPressed { event ->
                if (event.code == KeyCode.ESCAPE) {
                    suggestionMenu.hide()
                    input.clear()
                    onEscape()
                    event.consume()
                }
            }
            setOnAction { onSubmit() }
        }
        clear.apply {
            styleClass += "table-search-clear"
            accessibleText = "Clear search"
            isVisible = false
            isManaged = false
            setOnAction { input.clear(); input.requestFocus() }
        }
        children += listOf(input, clear)
        suggestionDelay.setOnFinished { requestSuggestions(input.text.orEmpty(), suggestionGeneration.get()) }
    }

    private fun updateSuggestions(value: String) {
        if (suggestions == null) return
        val query = value.trim()
        suggestionGeneration.incrementAndGet()
        suggestionDelay.stop()
        if (query.length < 2) {
            suggestionMenu.hide()
            return
        }
        suggestionDelay.playFromStart()
    }

    private fun requestSuggestions(query: String, generation: Long) {
        val provider = suggestions ?: return
        provider(query.trim()).whenComplete { matches, error ->
            javafx.application.Platform.runLater {
                if (generation != suggestionGeneration.get() || !input.isFocused) return@runLater
                val available = matches.orEmpty().map { match ->
                    MenuItem(
                        "${match.name}  ·  ${match.symbol}" +
                                match.exchange.takeIf(String::isNotBlank)?.let { "  ·  $it" }.orEmpty()
                    ).apply {
                        setOnAction {
                            suggestionMenu.hide()
                            onSuggestionSelected(match)
                        }
                    }
                }
                suggestionMenu.items.setAll(available.ifEmpty {
                    listOf(
                        MenuItem(if (error == null) "No supported stock found" else "Stock lookup unavailable")
                            .apply { isDisable = true })
                })
                if (!suggestionMenu.isShowing) suggestionMenu.show(input, javafx.geometry.Side.BOTTOM, 0.0, 3.0)
            }
        }
    }

    fun focusField() = input.requestFocus()

    fun clear(): Boolean {
        if (text.isEmpty()) return false
        input.clear()
        return true
    }
}