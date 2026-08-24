package org.senatov.mimitrends

import javafx.scene.control.Label
import javafx.scene.layout.VBox
import org.senatov.mimitrends.model.ScanResult

internal class SignalInspectorPanel : VBox(10.0) {
    private val title = Label("Select a signal").apply { styleClass += "inspector-title" }
    private val subtitle = Label("Signal details will appear here.").apply { styleClass += "inspector-subtitle" }
    private val timing = valueLabel("Quote, analysis, and evaluation times will appear here.")
    private val rationale = Label()
    private val risk = Label()
    private val model = Label()

    init {
        listOf(rationale, risk, model).forEach(::configureValueLabel)
        children.setAll(
            VBox(2.0, title, subtitle).apply { styleClass += "inspector-heading" },
            section("TIMELINE", timing),
            section("WHY SELECTED", rationale),
            section("ENTRY & RISK", risk),
            section("MODEL", model)
        )
        styleClass += "signal-inspector"
    }

    fun show(result: ScanResult) {
        title.text = result.symbol
        subtitle.text = result.signalSource.substringBefore(" ·")
        timing.text = FeedFreshness.timeline(result)
        rationale.text = SignalMetricPresentation.strength(result).details
        risk.text = result.entryQualityLabel + if (result.entryCooldownMinutes > 0) {
            " · Wait ${result.entryCooldownMinutes}m"
        } else " · Ready for review"
        model.text = when {
            result.continuationProbability.isFinite() ->
                "%.0f%% continuation · %s · %d samples".format(
                    result.continuationProbability * 100.0, result.predictionSource, result.predictionSamples)
            else -> "Insufficient validated history"
        }
    }

    private fun section(caption: String, value: Label) = VBox(5.0,
        Label(caption).apply { styleClass += "inspector-section-caption" }, value
    ).apply { styleClass += "inspector-section" }

    private fun valueLabel(initial: String = "") = Label(initial).also(::configureValueLabel)

    private fun configureValueLabel(label: Label) {
        label.isWrapText = true
        label.maxWidth = Double.MAX_VALUE
        label.styleClass += "inspector-line"
    }
}
