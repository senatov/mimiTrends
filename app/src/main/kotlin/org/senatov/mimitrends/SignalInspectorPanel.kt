package org.senatov.mimitrends

import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import org.senatov.mimitrends.model.ScanResult

internal class SignalInspectorPanel : VBox(7.0) {
    private val title = Label("Select a signal").apply { styleClass += "inspector-title" }
    private val timing = Label("Quote, analysis, and evaluation times will appear here.")
    private val rationale = Label()
    private val risk = Label()
    private val model = Label()

    init {
        listOf(timing, rationale, risk, model).forEach {
            it.isWrapText = true
            it.styleClass += "inspector-line"
        }
        children.setAll(title, timing, rationale, risk, model)
        padding = Insets(3.0)
        styleClass += "signal-inspector"
    }

    fun show(result: ScanResult) {
        title.text = "${result.symbol} · ${result.signalSource.substringBefore(" ·")}" 
        timing.text = FeedFreshness.timeline(result)
        rationale.text = "Why selected · ${SignalMetricPresentation.strength(result).details}"
        risk.text = "Entry · ${result.entryQualityLabel}" + if (result.entryCooldownMinutes > 0) {
            " · wait ${result.entryCooldownMinutes}m"
        } else " · ready for review"
        model.text = when {
            result.continuationProbability.isFinite() ->
                "Model · %.0f%% continuation · %s · %d samples".format(
                    result.continuationProbability * 100.0, result.predictionSource, result.predictionSamples)
            else -> "Model · insufficient validated history"
        }
    }
}
