package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.ResearchFeatures
import org.senatov.mimitrends.model.isValidMinuteBar
import kotlin.math.sqrt

/** Builds a bounded point-in-time feature snapshot from completed bars. */
object ResearchFeatureExtractor {
    fun extract(bars: List<MinuteBar>): ResearchFeatures? {
        val clean = bars.asSequence().filter(MinuteBar::isValidMinuteBar)
            .sortedBy(MinuteBar::minuteEpochSeconds).toList()
        val latest = clean.lastOrNull() ?: return null
        val zone = MarketTimeZone.forSymbol(latest.symbol)
        val latestDate = java.time.Instant.ofEpochSecond(latest.minuteEpochSeconds).atZone(zone).toLocalDate()
        val session = clean.filter {
            java.time.Instant.ofEpochSecond(it.minuteEpochSeconds).atZone(zone).toLocalDate() == latestDate
        }
        if (session.size < 2 || latest.close <= 0.0) return null
        val recent30 = trailingWindow(session, 30)
        val returns30 = recent30.zipWithNext().mapNotNull { (a, b) ->
            if (b.minuteEpochSeconds - a.minuteEpochSeconds == 60L) percent(b.close, a.close) else null
        }
        val mean = returns30.average()
        val volatility = if (mean.isFinite()) {
            sqrt(returns30.sumOf { (it - mean) * (it - mean) } / returns30.size)
        } else Double.NaN
        val recent10 = trailingWindow(session, 10)
        val prior30 = session.filter {
            val age = latest.minuteEpochSeconds - it.minuteEpochSeconds
            age in 11L * 60L..40L * 60L
        }
        val currentVolumes = recent10.filter { it.minuteEpochSeconds > latest.minuteEpochSeconds - 10L * 60L }
        val recentVolumes = prior30.map { it.volume }.filter { it > 0.0 }
        val currentVolume = currentVolumes.sumOf(MinuteBar::volume)
        val referenceVolume = recentVolumes.average().takeIf { it.isFinite() && it > 0.0 }
            ?.times(currentVolumes.size)
        // Ananth Madhavan, "VWAP Strategies" (2002), volume-weighted execution benchmark.
        // https://www.pm-research.com/content/iijtrade/2002/1/32
        val vwapDenominator = session.sumOf(MinuteBar::volume)
        val vwap = if (vwapDenominator > 0.0) session.sumOf { it.close * it.volume } / vwapDenominator else Double.NaN
        return ResearchFeatures(
            observedEpochSeconds = latest.minuteEpochSeconds,
            entryPrice = latest.close,
            return1mPercent = trailingReturn(session, 1),
            return3mPercent = trailingReturn(session, 3),
            return5mPercent = trailingReturn(session, 5),
            return10mPercent = trailingReturn(session, 10),
            return30mPercent = trailingReturn(session, 30),
            return60mPercent = trailingReturn(session, 60),
            range10mPercent = if (recent10.isEmpty()) Double.NaN else
                percent(recent10.maxOf(MinuteBar::high), recent10.minOf(MinuteBar::low)),
            realizedVolatility30m = volatility,
            vwapDistancePercent = percent(latest.close, vwap),
            sessionHighDistancePercent = percent(latest.close, session.maxOf(MinuteBar::high)),
            sessionLowDistancePercent = percent(latest.close, session.minOf(MinuteBar::low)),
            volumeRatio10m = referenceVolume?.let { currentVolume / it } ?: Double.NaN,
            trendEfficiency10m = efficiency(recent10)
        )
    }

    private fun trailingReturn(bars: List<MinuteBar>, minutes: Int): Double {
        val latest = bars.last()
        val target = latest.minuteEpochSeconds - minutes * 60L
        val reference = bars.lastOrNull { it.minuteEpochSeconds == target } ?: return Double.NaN
        return percent(latest.close, reference.close)
    }

    private fun trailingWindow(bars: List<MinuteBar>, minutes: Int): List<MinuteBar> {
        val cutoff = bars.last().minuteEpochSeconds - minutes * 60L
        return bars.filter { it.minuteEpochSeconds >= cutoff }
    }

    private fun efficiency(bars: List<MinuteBar>): Double {
        if (bars.size < 2) return Double.NaN
        val path = bars.zipWithNext().sumOf { (a, b) -> kotlin.math.abs(b.close - a.close) }
        return if (path > 0.0) (bars.last().close - bars.first().close) / path else 0.0
    }

    private fun percent(value: Double, reference: Double): Double =
        if (value.isFinite() && reference.isFinite() && reference > 0.0) (value / reference - 1.0) * 100.0 else Double.NaN
}
