package org.senatov.mimitrends

import javafx.animation.PauseTransition
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.util.Duration
import org.senatov.mimitrends.model.ScanResult

internal object ScannerTableInteraction {
    fun install(
        table: TableView<ScanResult>,
        open: (ScanResult) -> Unit,
        inspect: (ScanResult) -> Unit,
        copySearch: (ScanResult) -> Unit,
        copyTicker: (String) -> Unit,
        openStock: (String) -> Unit,
        clearSearch: () -> Boolean,
        isPinned: (String) -> Boolean,
        removePinned: (String) -> Unit
    ) {
        table.setRowFactory {
            object : TableRow<ScanResult>() {
                var contextItem: ScanResult? = null
                val hoverDelay = PauseTransition(Duration.seconds(5.0)).apply {
                    setOnFinished { item?.takeIf { isHover && !isEmpty }?.let(inspect) }
                }
                val removeItem = MenuItem("Remove from watchlist").apply {
                    setOnAction { contextItem?.symbol?.let(removePinned) }
                }

                init {
                    setOnMouseEntered { if (!isEmpty) hoverDelay.playFromStart() }
                    setOnMouseExited { hoverDelay.stop() }
                    setOnMouseClicked { event ->
                        if (!isEmpty && event.button == MouseButton.PRIMARY && event.clickCount == 1) {
                            hoverDelay.stop()
                            open(item)
                        }
                    }
                    contextMenu = ContextMenu(
                        MenuItem("Copy search keyword").apply {
                            accelerator = KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
                            setOnAction { contextItem?.let(copySearch) }
                        },
                        MenuItem("Copy ticker").apply { setOnAction { contextItem?.symbol?.let(copyTicker) } },
                        MenuItem("Open Stock").apply {
                            accelerator = KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN)
                            setOnAction { contextItem?.symbol?.let(openStock) }
                        },
                        removeItem
                    ).apply {
                        setOnShowing {
                            contextItem = item.takeUnless { isEmpty }
                            removeItem.isVisible = contextItem?.symbol?.let(isPinned) == true
                            contextItem?.let { table.selectionModel.select(it) }
                        }
                        setOnHidden { contextItem = null }
                    }
                }

                override fun updateItem(item: ScanResult?, empty: Boolean) {
                    super.updateItem(item, empty)
                    styleClass.remove("user-watchlist-row")
                    if (!empty && item != null && isPinned(item.symbol)) styleClass += "user-watchlist-row"
                }
            }
        }
        table.setOnKeyPressed { event ->
            val selected = table.selectionModel.selectedItem
            when {
                event.code == KeyCode.ENTER -> selected?.let(open)
                event.code == KeyCode.SPACE -> selected?.let(inspect)
                event.code == KeyCode.O && event.isShortcutDown -> selected?.symbol?.let(openStock)
                event.code == KeyCode.C && event.isShortcutDown -> selected?.let(copySearch)
                event.code == KeyCode.ESCAPE && clearSearch() -> Unit
                else -> return@setOnKeyPressed
            }
            event.consume()
        }
    }
}
