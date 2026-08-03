package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowGeometryServiceTest {
    @TempDir lateinit var directory: Path

    @Test fun `persists geometry independently for each window key`() {
        val path = directory.resolve("windows.properties")
        val settings = WindowGeometryService("settings", 897.0, 720.0, path)
        val about = WindowGeometryService("about", 680.0, 500.0, path)

        settings.saveBounds(WindowBounds(120.0, 80.0, 940.0, 760.0))
        about.saveBounds(WindowBounds(300.0, 200.0, 680.0, 500.0))

        assertEquals(WindowBounds(120.0, 80.0, 940.0, 760.0), settings.loadBounds())
        assertEquals(WindowBounds(300.0, 200.0, 680.0, 500.0), about.loadBounds())
    }

    @Test fun `returns no geometry before the first window session`() {
        val service = WindowGeometryService("settings", 897.0, 720.0, directory.resolve("missing.properties"))
        assertNull(service.loadBounds())
    }
}
