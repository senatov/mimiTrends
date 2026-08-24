package org.senatov.mimitrends

import org.senatov.mimitrends.model.ProviderMinuteBar
import org.senatov.mimitrends.model.ScanResult

internal class ProviderObservationPresenter(
    private val panel: ScannerPanel,
    private val selectedSignal: () -> ScanResult?,
    private val updateSelectedSignal: (ScanResult) -> Unit,
    private val requestShortMoveRefresh: () -> Unit
) {
    fun apply(observation: ProviderMinuteBar) {
        panel.applyMarketObservation(
            observation.symbol, observation.bar.close, observation.observedAtMillis, observation.provider
        )
        selectedSignal()?.takeIf {
            it.symbol == observation.symbol && observation.observedAtMillis > it.updatedAtMillis
        }?.let { signal ->
            updateSelectedSignal(signal.copy(
                price = observation.bar.close,
                updatedAtMillis = observation.observedAtMillis,
                dataStatus = observation.provider
            ))
        }
        requestShortMoveRefresh()
    }
}
