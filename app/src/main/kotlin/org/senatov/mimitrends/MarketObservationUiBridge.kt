package org.senatov.mimitrends

import javafx.application.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.senatov.mimitrends.model.ProviderMinuteBar

internal class MarketObservationUiBridge(
    observations: Flow<ProviderMinuteBar>,
    private val applyObservation: (ProviderMinuteBar) -> Unit
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            observations.collect { observation ->
                Platform.runLater { applyObservation(observation) }
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
