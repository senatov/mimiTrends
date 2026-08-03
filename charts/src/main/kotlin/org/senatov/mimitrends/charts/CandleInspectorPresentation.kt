package org.senatov.mimitrends.charts

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.VolumeStatus
import java.text.SimpleDateFormat
import java.util.Date

internal object CandleInspectorPresentation {
    fun text(bar: MinuteBar, priceMultiplier: Double, currencySymbol: String, dateFormat: SimpleDateFormat): String {
        val price = { value: Double -> "$currencySymbol${"%,.2f".format(value * priceMultiplier)}" }
        val change = percent(bar.open, bar.close - bar.open)
        val range = percent(bar.open, bar.high - bar.low)
        val direction = when {
            bar.close > bar.open -> "UP"
            bar.close < bar.open -> "DOWN"
            else -> "FLAT"
        }
        val volume = when (bar.volumeStatus) {
            VolumeStatus.REPORTED -> TrendChartSupport.compactVolume(bar.volume)
            VolumeStatus.ZERO -> "0 (reported)"
            VolumeStatus.MISSING -> "unavailable"
            VolumeStatus.ESTIMATED -> "${TrendChartSupport.compactVolume(bar.volume)} (partial)"
        }
        return "${dateFormat.format(Date(bar.minuteEpochSeconds * 1_000))}  ·  $direction ${"%+.2f".format(change)}%" +
            "  ·  Range ${"%.2f".format(range)}%\nO ${price(bar.open)}   H ${price(bar.high)}   L ${price(bar.low)}" +
            "   C ${price(bar.close)}   ·   Volume $volume  ·  LIVE"
    }

    private fun percent(base: Double, difference: Double) = if (base != 0.0) difference / base * 100.0 else 0.0
}
