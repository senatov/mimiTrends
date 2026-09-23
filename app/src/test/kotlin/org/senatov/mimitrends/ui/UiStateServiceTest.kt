package org.senatov.mimitrends.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class UiStateServiceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `uses wider opportunities panel for new UI state`() {
        val state = UiStateService(directory.resolve("missing.properties")).load()

        assertEquals(0.60, state.tableDividerPosition, 0.0001)
        assertEquals(820.0, state.width, 0.0001)
        assertEquals(500.0, state.height, 0.0001)
        assertEquals(false, state.chartVisible)
    }

    @Test
    fun `widens legacy opportunities panel once`() {
        val path = directory.resolve("legacy.properties")
        Files.writeString(path, "tableDividerPosition=0.62\n")

        val state = UiStateService(path).load()

        assertEquals(0.59, state.tableDividerPosition, 0.0001)
    }

    @Test
    fun `preserves manually resized panel after layout migration`() {
        val path = directory.resolve("current.properties")
        Files.writeString(path, "tableDividerPosition=0.67\ntableDividerLayoutVersion=2\n")

        val state = UiStateService(path).load()

        assertEquals(0.67, state.tableDividerPosition, 0.0001)
    }

    @Test
    fun `compacts legacy oversized workspace once`() {
        val path = directory.resolve("legacy-size.properties")
        Files.writeString(path, "width=1696\nheight=1263\n")

        val state = UiStateService(path).load()

        assertEquals(880.0, state.width, 0.0001)
        assertEquals(560.0, state.height, 0.0001)
    }

    @Test
    fun `preserves resized compact workspace after migration`() {
        val path = directory.resolve("current-size.properties")
        Files.writeString(path, "width=1040\nheight=700\nworkspaceLayoutVersion=3\nchartVisible=true\n")

        val state = UiStateService(path).load()

        assertEquals(1040.0, state.width, 0.0001)
        assertEquals(700.0, state.height, 0.0001)
        assertEquals(true, state.chartVisible)
    }
}
