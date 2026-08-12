package org.senatov.mimitrends

import javafx.geometry.Rectangle2D
import javafx.stage.Screen
import javafx.stage.Stage
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class UiState(
    val x: Double? = null, val y: Double? = null, val width: Double = 1120.0, val height: Double = 720.0,
    val maximized: Boolean = false, val symbol: String = "AAPL", val range: String = "3M", val dividerPosition: Double = 0.34,
    val scannerColumns: String = "", val shortMoveColumns: String = "", val tableDividerPosition: Double = 0.68
)

class UiStateService(private val path: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "ui-state.properties")) {
    private val log = LoggerFactory.getLogger(javaClass)
    private var normalBounds = Rectangle2D(0.0, 0.0, 1120.0, 720.0)

    fun load(): UiState {
        log.debug(LogTag.IO, "load(path={})", path)
        if (!Files.exists(path)) return UiState()
        return runCatching {
            val p = Properties().also { Files.newInputStream(path).use(it::load) }
            UiState(p.getProperty("x")?.toDouble(), p.getProperty("y")?.toDouble(),
                p.getProperty("width", "1120").toDouble(), p.getProperty("height", "720").toDouble(),
                p.getProperty("maximized", "false").toBoolean(), p.getProperty("symbol", "AAPL"), p.getProperty("range", "3M"),
                p.getProperty("dividerPosition", "0.34").toDouble().coerceIn(0.15, 0.75),
                p.getProperty("scannerColumns", ""), p.getProperty("shortMoveColumns", ""),
                p.getProperty("tableDividerPosition", "0.68").toDouble().coerceIn(0.45, 0.82))
        }.onFailure { log.error(LogTag.IO, "UI state load failed", it) }.getOrDefault(UiState())
    }

    fun restore(stage: Stage, state: UiState) {
        log.debug(LogTag.UI, "restore(width={}, height={}, maximized={})", state.width, state.height, state.maximized)
        val primary = Screen.getPrimary().visualBounds
        val width = state.width.coerceIn(stage.minWidth, Screen.getScreens().maxOf { it.visualBounds.width })
        val height = state.height.coerceIn(stage.minHeight, Screen.getScreens().maxOf { it.visualBounds.height })
        var x = state.x ?: (primary.minX + (primary.width - width) / 2)
        var y = state.y ?: (primary.minY + (primary.height - height) / 2)
        val candidate = Rectangle2D(x, y, width, height)
        if (Screen.getScreens().none { overlaps(candidate, it.visualBounds) }) {
            x = primary.minX + (primary.width - width) / 2; y = primary.minY + (primary.height - height) / 2
        }
        stage.x = x; stage.y = y; stage.width = width; stage.height = height
        normalBounds = Rectangle2D(x, y, width, height)
        stage.isMaximized = state.maximized
        attachTracking(stage)
    }

    fun save(stage: Stage, symbol: String, range: String, dividerPosition: Double,
             scannerColumns: String, shortMoveColumns: String, tableDividerPosition: Double) {
        log.debug(LogTag.IO, "save(symbol={}, range={}, maximized={}, divider={})", symbol, range, stage.isMaximized, dividerPosition)
        if (!stage.isMaximized && !stage.isFullScreen) normalBounds = Rectangle2D(stage.x, stage.y, stage.width, stage.height)
        Files.createDirectories(path.parent)
        val p = Properties().apply {
            setProperty("x", normalBounds.minX.toString()); setProperty("y", normalBounds.minY.toString())
            setProperty("width", normalBounds.width.toString()); setProperty("height", normalBounds.height.toString())
            setProperty("maximized", stage.isMaximized.toString()); setProperty("symbol", symbol); setProperty("range", range)
            setProperty("dividerPosition", dividerPosition.coerceIn(0.15, 0.75).toString())
            setProperty("scannerColumns", scannerColumns); setProperty("shortMoveColumns", shortMoveColumns)
            setProperty("tableDividerPosition", tableDividerPosition.coerceIn(0.45, 0.82).toString())
        }
        Files.newOutputStream(path).use { p.store(it, "MiMiTrends UI state") }
    }

    private fun attachTracking(stage: Stage) {
        log.debug(LogTag.UI, "attachTracking()")
        val update = { _: Any? -> if (!stage.isMaximized && !stage.isFullScreen && stage.width > 0 && stage.height > 0) normalBounds = Rectangle2D(stage.x, stage.y, stage.width, stage.height) }
        stage.xProperty().addListener { _, _, value -> update(value) }; stage.yProperty().addListener { _, _, value -> update(value) }
        stage.widthProperty().addListener { _, _, value -> update(value) }; stage.heightProperty().addListener { _, _, value -> update(value) }
    }

    private fun overlaps(a: Rectangle2D, b: Rectangle2D): Boolean {
        log.trace(LogTag.UI, "overlaps()")
        return minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX) >= 100 && minOf(a.maxY, b.maxY) - maxOf(a.minY, b.minY) >= 80
    }
}
