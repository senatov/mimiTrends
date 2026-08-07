package org.senatov.mimitrends.charts

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import java.text.SimpleDateFormat
import java.util.Date

internal object SignalChartPresentation {
    fun nearestBar(bars: List<MinuteBar>, signal: ScanResult): MinuteBar? {
        val epoch = signal.signalEpochMillis / 1_000L
        return bars.minByOrNull { kotlin.math.abs(it.minuteEpochSeconds - epoch) }
    }

    fun summary(
        signal: ScanResult,
        signalBar: MinuteBar?,
        currentPrice: Double,
        priceMultiplier: Double,
        currencySymbol: String
    ): String {
        val age = if (signal.signalAgeMinutes == 0) "now" else "${signal.signalAgeMinutes}m ago"
        val date = signalBar?.let { SimpleDateFormat("dd MMM HH:mm").format(Date(it.minuteEpochSeconds * 1_000)) } ?: "—"
        val entry = signal.signalPrice.takeIf { it.isFinite() && it > 0.0 }?.times(priceMultiplier)
        val move = entry?.takeIf { it != 0.0 }?.let { (currentPrice - it) / it * 100.0 }
        val prices = entry?.let {
            " · Entry $currencySymbol${"%,.2f".format(it)} → now $currencySymbol${"%,.2f".format(currentPrice)} (${move?.let { value -> "%+.2f%%".format(value) } ?: "—"})"
        }.orEmpty()
        val metrics = listOfNotNull(
            signal.priceAnomaly.finiteMetric("Jump", "σ"),
            signal.rangeAnomaly.finiteMetric("Range", "σ"),
            signal.volumeAnomaly.finiteMetric("Volume", "σ"),
            signal.relativeVolume.finiteMetric("RVOL", "×")
        ).joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }
        return "${signal.signalSource.uppercase()} · $date · $age$prices · Score ${"%.2f".format(signal.anomalyScore)}×$metrics"
    }

    private fun Double.finiteMetric(label: String, suffix: String): String? =
        takeIf(Double::isFinite)?.let { "$label ${"%.2f".format(it)}$suffix" }
}
