package org.senatov.mimitrends.shortmove

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Separator
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.senatov.mimitrends.marketdata.CoverageItem
import org.senatov.mimitrends.marketdata.CoverageResult
import org.senatov.mimitrends.marketdata.RecentCoverageClient
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

internal class ShortMoveCoverageWindow(private val openExternal: (String) -> Unit) {
    private val client = RecentCoverageClient()
    private val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "recent-coverage").apply { isDaemon = true }
    }
    private val clock = DateTimeFormatter.ofPattern("dd MMM yy HH:mm")
        .withZone(ZoneId.systemDefault())
    fun show(anchor: javafx.scene.Node, symbol: String, companyName: String) {
        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            styleClass += "rumors-content"
        }
        content.children += Label("Recent coverage · $symbol").apply { styleClass += "rumors-title" }
        content.children += Label("Loading recent messages…")
        val scroll = ScrollPane(content).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            styleClass += anchor.scene.root.styleClass.filter {
                it.startsWith("theme-") || it.startsWith("density-")
            }
        }
        val scene = Scene(scroll, 520.0, 390.0).apply {
            stylesheets.setAll(anchor.scene.stylesheets)
        }
        val window = Stage().apply {
            initOwner(anchor.scene.window)
            title = "Recent coverage · $symbol"
            this.scene = scene
            minWidth = 360.0
            minHeight = 240.0
            show()
        }
        executor.execute {
            val result = runCatching { client.load(symbol, companyName) }
            Platform.runLater {
                if (!window.isShowing) return@runLater
                content.children.setAll(Label("Recent coverage · $symbol").apply { styleClass += "rumors-title" })
                result.onSuccess { render(content, it) }
                    .onFailure { content.children += Label("News sources are unavailable. Try again later.") }
            }
        }
    }

    private fun render(content: VBox, result: CoverageResult) {
        if (result.items.isEmpty()) {
            val message = if (result.unavailableSources.size == 3) "News sources are unavailable. Try again later."
            else "No recent messages found for this stock."
            content.children += Label(message)
        } else {
            result.items.forEach { content.children += itemView(it) }
        }
        if (result.unavailableSources.isNotEmpty()) {
            content.children += Label("Unavailable: ${result.unavailableSources.joinToString()}").apply {
                styleClass += "rumors-meta"
            }
        }
    }

    private fun itemView(item: CoverageItem): VBox = VBox(3.0).apply {
        children += Separator()
        children += Hyperlink(item.title).apply {
            isWrapText = true
            styleClass += "rumors-link"
            setOnAction { openExternal(item.url.toString()) }
        }
        val time = item.publishedLabel ?: item.publishedAt?.let(clock::format) ?: "Time unknown"
        children += Label("${item.publisher} · $time").apply { styleClass += "rumors-meta" }
    }
}
