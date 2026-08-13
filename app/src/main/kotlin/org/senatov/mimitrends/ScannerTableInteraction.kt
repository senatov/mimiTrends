package org.senatov.mimitrends

import javafx.animation.PauseTransition
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.util.Duration
import org.senatov.mimitrends.model.ScanResult

internal object ScannerTableInteraction {
    fun install(
        table: TableView<ScanResult>,
        open: (ScanResult) -> Unit,
        inspect: (ScanResult) -> Unit,
        copySearch: (ScanResult) -> Unit,
        copyTicker: (String) -> Unit
    ) {
        table.setRowFactory {
            TableRow<ScanResult>().apply {
                var contextItem: ScanResult? = null
                val hoverDelay = PauseTransition(Duration.seconds(5.0)).apply {
                    setOnFinished { item?.takeIf { isHover && !isEmpty }?.let(inspect) }
                }
                setOnMouseEntered { if (!isEmpty) hoverDelay.playFromStart() }
                setOnMouseExited { hoverDelay.stop() }
                setOnMouseClicked { event ->
                    if (!isEmpty && event.button == MouseButton.PRIMARY && event.clickCount == 1) {
                        hoverDelay.stop(); open(item); inspect(item)
                    }
                }
                contextMenu = ContextMenu(
                    MenuItem("Copy search keyword").apply { setOnAction { contextItem?.let(copySearch) } },
                    MenuItem("Copy ticker").apply { setOnAction { contextItem?.symbol?.let(copyTicker) } }
                ).apply {
                    setOnShowing {
                        contextItem = item.takeUnless { isEmpty }
                        contextItem?.let { table.selectionModel.select(it) }
                    }
                    setOnHidden { contextItem = null }
                }
            }
        }
        table.setOnKeyPressed { event ->
            if (event.code == KeyCode.C && event.isShortcutDown) {
                table.selectionModel.selectedItem?.let(copySearch); event.consume()
            }
        }
    }
}
