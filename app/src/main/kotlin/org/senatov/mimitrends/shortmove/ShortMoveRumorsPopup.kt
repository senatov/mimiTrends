package org.senatov.mimitrends.shortmove

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Separator
import javafx.scene.layout.VBox
import javafx.stage.Popup
import org.senatov.mimitrends.marketdata.CoverageItem
import org.senatov.mimitrends.marketdata.CoverageResult
import org.senatov.mimitrends.marketdata.RecentCoverageClient
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

internal class ShortMoveRumorsPopup(private val openExternal: (String) -> Unit) {
    private val client = RecentCoverageClient()
    private val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "recent-coverage").apply { isDaemon = true }
    }
    private val clock = DateTimeFormatter.ofPattern("dd MMM HH:mm")
        .withZone(ZoneId.systemDefault())
    private var popup: Popup? = null

    fun show(anchor: javafx.scene.Node, symbol: String, companyName: String) {
        popup?.hide()
        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            prefWidth = 455.0
            styleClass += "rumors-popup"
        }
        content.children += Label("Recent coverage · $symbol").apply { styleClass += "rumors-title" }
        content.children += Label("Loading recent messages…")
        val scroll = ScrollPane(content).apply {
            isFitToWidth = true
            prefViewportHeight = 340.0
            maxHeight = 390.0
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        }
        val current = Popup().apply {
            isAutoHide = true
            isHideOnEscape = true
            this.content.add(scroll)
        }
        popup = current
        val bounds = anchor.localToScreen(anchor.boundsInLocal) ?: return
        current.show(anchor, bounds.minX, bounds.maxY + 3.0)
        executor.execute {
            val result = runCatching { client.load(symbol, companyName) }
            Platform.runLater {
                if (popup !== current || !current.isShowing) return@runLater
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
