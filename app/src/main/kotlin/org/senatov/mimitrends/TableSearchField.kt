package org.senatov.mimitrends

import javafx.scene.control.TextField
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCode
import javafx.scene.layout.HBox
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
    }

    private fun updateSuggestions(value: String) {
        val provider = suggestions ?: return
        val query = value.trim()
        val generation = suggestionGeneration.incrementAndGet()
        if (query.length < 2) {
            suggestionMenu.hide()
            return
        }
        provider(query).whenComplete { matches, error ->
            javafx.application.Platform.runLater {
                if (generation != suggestionGeneration.get() || error != null || !input.isFocused) return@runLater
                suggestionMenu.items.setAll(matches.orEmpty().map { match ->
                    MenuItem(
                        "${match.name}  ·  ${match.symbol}" +
                            match.exchange.takeIf(String::isNotBlank)?.let { "  ·  $it" }.orEmpty()
                    ).apply {
                        setOnAction {
                            suggestionMenu.hide()
                            onSuggestionSelected(match)
                        }
                    }
                })
                if (suggestionMenu.items.isEmpty()) suggestionMenu.hide()
                else if (!suggestionMenu.isShowing) suggestionMenu.show(input, javafx.geometry.Side.BOTTOM, 0.0, 3.0)
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