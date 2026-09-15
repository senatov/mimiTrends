package org.senatov.mimitrends

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class MarketAnalysisStartupOverlayTest {
    @Test fun `startup notice renders and disappears after completion`() {
        val completed = CompletableFuture<Unit>()
        Platform.startup {
            try {
                val overlay = MarketAnalysisStartupOverlay()
                val root = StackPane(overlay)
                val scene = Scene(root, 1120.0, 64.0)
                scene.stylesheets.add(javaClass.getResource("Workspace.css")!!.toExternalForm())
                root.applyCss()
                root.layout()
                assertTrue(overlay.isVisible)
                assertTrue(overlay.height >= 40.0)
                val snapshot = root.snapshot(null, null)
                assertTrue(snapshot.width >= scene.width)
                overlay.showFailure()
                assertTrue(overlay.isVisible)
                overlay.resume()
                assertTrue(overlay.isVisible)
                overlay.finish()
                assertFalse(overlay.isVisible)
                assertFalse(overlay.isManaged)
                overlay.resume()
                assertFalse(overlay.isVisible)
                completed.complete(Unit)
            } catch (error: Throwable) {
                completed.completeExceptionally(error)
            }
        }
        completed.get(20, TimeUnit.SECONDS)
    }
}
