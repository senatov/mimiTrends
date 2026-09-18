package org.senatov.mimitrends.providers

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

import org.senatov.mimitrends.model.ScanResult

internal class ProviderObservationPresenter(
    private val panel: ScannerPanel,
    private val selectedSignal: () -> ScanResult?,
    private val updateSelectedSignal: (ScanResult) -> Unit,
    private val requestShortMoveRefresh: () -> Unit,
    private val onObservation: (MarketPriceObservation) -> Unit = {}
) {
    fun apply(observation: MarketPriceObservation) {
        onObservation(observation)
        panel.applyMarketObservation(
            observation.symbol, observation.price, observation.observedAtMillis, observation.provider
        )
        selectedSignal()?.takeIf {
            it.symbol == observation.symbol && observation.observedAtMillis > it.updatedAtMillis
        }?.let { signal ->
            updateSelectedSignal(
                signal.copy(
                    price = observation.price,
                    updatedAtMillis = observation.observedAtMillis,
                    dataStatus = observation.provider
                )
            )
        }
        requestShortMoveRefresh()
    }
}