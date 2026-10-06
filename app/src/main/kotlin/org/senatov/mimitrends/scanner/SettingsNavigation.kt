package org.senatov.mimitrends.scanner

import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.geometry.Side
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Control
import javafx.scene.control.CustomMenuItem
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox

internal object SettingsNavigation {
    fun section(title: String, vararg content: Node): VBox = VBox(10.0).apply {
        styleClass += "settings-glass-card"
        children += Label(title).apply { styleClass += "settings-section-title" }
        children += content
    }

    fun row(title: String, detail: String, control: Control): HBox = HBox(12.0).apply {
        alignment = Pos.CENTER_LEFT
        val description = HBox(
            6.0, Label(title).apply { styleClass += "settings-row-title" },
            helpButton(title, detail)
        ).apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 260.0; prefWidth = 300.0; maxWidth = 330.0
        }
        control.minWidth = 180.0
        control.maxWidth = Double.MAX_VALUE
        HBox.setHgrow(control, Priority.ALWAYS)
        children += listOf(description, control)
    }

    private fun helpButton(title: String, detail: String): Button = Button("i").apply {
        styleClass += "settings-info-button"
        isFocusTraversable = false
        tooltip = Tooltip(detail)
        accessibleText = "$title information"
        setOnAction {
            val anchor = this
            val message = Label(detail).apply {
                isWrapText = true
                maxWidth = 300.0
                styleClass += "settings-info-content"
            }
            ContextMenu(CustomMenuItem(message, false)).apply {
                styleClass += "settings-info-popup"
                show(anchor, Side.BOTTOM, 0.0, 4.0)
            }
        }
    }

    fun create(pages: List<Pair<String, VBox>>): HBox {
        val titles = FXCollections.observableArrayList(pages.map(Pair<String, VBox>::first))
        val heading = Label().apply { styleClass += "settings-page-title" }
        val subtitle = Label().apply { styleClass += "settings-page-subtitle" }
        val content = StackPane().apply { styleClass += "settings-page-content" }
        val navigation = ListView(titles).apply {
            styleClass += "settings-sidebar-list"
            prefWidth = 205.0
            minWidth = 180.0
            maxWidth = 220.0
            fixedCellSize = 39.0
            prefHeight = 300.0
        }
        val sidebar = VBox(
            12.0,
            Label("PREFERENCES").apply { styleClass += "settings-sidebar-caption" }, navigation
        ).apply {
            styleClass += "settings-sidebar"
            prefWidth = 205.0
            minWidth = 180.0
            maxWidth = 220.0
            VBox.setVgrow(navigation, Priority.ALWAYS)
        }
        val header = VBox(3.0, heading, subtitle).apply { styleClass += "settings-page-header" }
        val body = BorderPane().apply {
            top = header
            center = content
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        navigation.selectionModel.selectedIndexProperty().addListener { _, _, selected ->
            val index = selected.toInt()
            if (index !in pages.indices) return@addListener
            heading.text = pages[index].first
            subtitle.text = when (index) {
                0 -> "Scanner rules, universe and refresh"
                1 -> "Thresholds for sudden price changes"
                2 -> "Quote collectors and external charts"
                else -> "Workspace and Live radar colours"
            }
            content.children.setAll(ScrollPane(pages[index].second).apply {
                isFitToWidth = true
                styleClass += "settings-scroll"
            })
        }
        navigation.selectionModel.selectFirst()
        return HBox(sidebar, body).apply {
            alignment = Pos.TOP_LEFT
            styleClass += "settings-navigation"
        }
    }
}