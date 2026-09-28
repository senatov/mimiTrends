package org.senatov.mimitrends.ui

import javafx.application.Platform
import org.senatov.mimitrends.providers.ScalablePollingService
import org.senatov.mimitrends.providers.SourceActivity
import org.senatov.mimitrends.services.ScalableLoginCoordinator
import org.senatov.mimitrends.services.ScalableLoginEvent
import java.util.concurrent.atomic.AtomicBoolean

internal class ScalableLoginUi(
    private val activity: SourceActivity,
    private val polling: ScalablePollingService,
    private val status: MainStatusController,
    private val coordinator: ScalableLoginCoordinator = ScalableLoginCoordinator()
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun start() {
        if (closed.get()) return
        if (coordinator.start { event -> Platform.runLater { if (!closed.get()) present(event) } }) {
            activity.markStatus("Scalable", "Signing in")
        }
    }

    private fun present(event: ScalableLoginEvent) {
        when (event) {
            ScalableLoginEvent.OPENED -> status.update("Complete Scalable login in Terminal and your browser")
            ScalableLoginEvent.READY -> {
                activity.markStatus("Scalable", "Ready")
                polling.requestRefresh()
                status.transientSuccess("Scalable session ready")
            }

            ScalableLoginEvent.TIMED_OUT -> {
                activity.markStatus("Scalable", "Login needed", failed = true)
                status.warning("Scalable login not completed; use Scalable login to retry")
            }

            ScalableLoginEvent.FAILED -> {
                activity.markStatus("Scalable", "Login needed", failed = true)
                status.warning("Could not open Scalable login; run sc login --local-read-only in Terminal")
            }
        }
    }

    override fun close() {
        closed.set(true)
        coordinator.close()
    }
}