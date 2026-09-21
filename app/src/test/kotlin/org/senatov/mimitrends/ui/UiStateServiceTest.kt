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
}
