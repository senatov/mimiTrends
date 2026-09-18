package org.senatov.mimitrends.market

import org.senatov.mimitrends.application.*
import org.senatov.mimitrends.ui.*
import org.senatov.mimitrends.scanner.*
import org.senatov.mimitrends.shortmove.*
import org.senatov.mimitrends.signals.*
import org.senatov.mimitrends.research.*
import org.senatov.mimitrends.market.*
import org.senatov.mimitrends.providers.*
import org.senatov.mimitrends.company.*
import org.senatov.mimitrends.services.*
import org.senatov.mimitrends.shared.*

import javafx.application.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

internal class MarketObservationUiBridge(
    observations: Flow<MarketPriceObservation>,
    private val applyObservation: (MarketPriceObservation) -> Unit
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uiUpdates = UiUpdateBatcher<String, MarketPriceObservation>({ task -> Platform.runLater(task) }) { batch ->
        batch.forEach(applyObservation)
    }

    init {
        scope.launch {
            observations.collect { observation ->
                uiUpdates.offer(observation.symbol, observation)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}