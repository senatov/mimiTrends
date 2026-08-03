package org.senatov.mimitrends

import javafx.geometry.Rectangle2D
import javafx.scene.control.Dialog
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.Window
import org.senatov.mimitrends.log.LogTag
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

internal data class WindowBounds(val x: Double, val y: Double, val width: Double, val height: Double)

internal class WindowGeometryService(
    private val key: String,
    private val defaultWidth: Double,
    private val defaultHeight: Double,
    private val path: Path = Path.of(System.getProperty("user.home"), ".mimi", "trends", "window-state.properties")
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun attach(dialog: Dialog<*>) {
        dialog.setOnShown { restore(dialog.dialogPane.scene.window) }
        dialog.setOnHidden { save(dialog.dialogPane.scene.window) }
    }

    internal fun loadBounds(): WindowBounds? {
        if (!Files.exists(path)) return null
        return runCatching {
            val properties = Properties().also { values -> Files.newInputStream(path).use(values::load) }
            WindowBounds(
                properties.getProperty("$key.x").toDouble(), properties.getProperty("$key.y").toDouble(),
                properties.getProperty("$key.width").toDouble(), properties.getProperty("$key.height").toDouble()
            )
        }.onFailure { log.warn(LogTag.IO, "window geometry load failed key={}", key, it) }.getOrNull()
    }

    internal fun saveBounds(bounds: WindowBounds) {
        runCatching {
            Files.createDirectories(path.parent)
            val properties = Properties()
            if (Files.exists(path)) Files.newInputStream(path).use(properties::load)
            properties.setProperty("$key.x", bounds.x.toString())
            properties.setProperty("$key.y", bounds.y.toString())
            properties.setProperty("$key.width", bounds.width.toString())
            properties.setProperty("$key.height", bounds.height.toString())
            Files.newOutputStream(path).use { properties.store(it, "MiMiTrends window geometry") }
        }.onFailure { log.warn(LogTag.IO, "window geometry save failed key={}", key, it) }
    }

    private fun restore(window: Window) {
        val screens = Screen.getScreens().map(Screen::getVisualBounds)
        val primary = Screen.getPrimary().visualBounds
        val stored = loadBounds()
        val width = (stored?.width ?: defaultWidth).coerceIn(MIN_WIDTH, screens.maxOf { it.width })
        val height = (stored?.height ?: defaultHeight).coerceIn(MIN_HEIGHT, screens.maxOf { it.height })
        val owner = (window as? Stage)?.owner
        val centeredX = owner?.let { it.x + (it.width - width) / 2.0 }
            ?: (primary.minX + (primary.width - width) / 2.0)
        val centeredY = owner?.let { it.y + (it.height - height) / 2.0 }
            ?: (primary.minY + (primary.height - height) / 2.0)
        var candidate = WindowBounds(stored?.x ?: centeredX, stored?.y ?: centeredY, width, height)
        if (screens.none { overlaps(candidate, it) }) candidate = WindowBounds(centeredX, centeredY, width, height)
        window.x = candidate.x
        window.y = candidate.y
        window.width = candidate.width
        window.height = candidate.height
    }

    private fun save(window: Window) {
        if (window.width > 0.0 && window.height > 0.0) {
            saveBounds(WindowBounds(window.x, window.y, window.width, window.height))
        }
    }

    private fun overlaps(bounds: WindowBounds, screen: Rectangle2D): Boolean =
        minOf(bounds.x + bounds.width, screen.maxX) - maxOf(bounds.x, screen.minX) >= MIN_VISIBLE_WIDTH &&
            minOf(bounds.y + bounds.height, screen.maxY) - maxOf(bounds.y, screen.minY) >= MIN_VISIBLE_HEIGHT

    private companion object {
        const val MIN_WIDTH = 560.0
        const val MIN_HEIGHT = 420.0
        const val MIN_VISIBLE_WIDTH = 120.0
        const val MIN_VISIBLE_HEIGHT = 80.0
    }
}
