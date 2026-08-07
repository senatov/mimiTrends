package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MarketTimeZone
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import java.time.Instant
import java.time.ZoneId

internal class LongCandidateSafetyFilter(private val zoneOverride: ZoneId? = null) {
    private val entryTiming = EntryTimingClassifier(zoneOverride)

    fun classify(bars: List<MinuteBar>, result: ScanResult): ScanResult? {
        if ('↓' in result.signalSource) {
            return result.takeIf(::isSharpDownsideWatch)?.asWatch("downside watch")
        }
        val safetyClassified = if (hasLongRisk(bars)) result.asWatch("watch") else result
        return entryTiming.classify(bars, safetyClassified)
    }

    private fun hasLongRisk(bars: List<MinuteBar>): Boolean {
        val latest = bars.lastOrNull() ?: return true
        val latestDate = local(latest).toLocalDate()
        val session = bars.filter { local(it).toLocalDate() == latestDate }
        if (session.size < MIN_SESSION_BARS) return true
        val recent = session.filter { it.minuteEpochSeconds >= latest.minuteEpochSeconds - LOOKBACK_MINUTES * 60L }
        if (recent.size < MIN_RECENT_BARS) return true
        return sessionReturn(session) <= MAX_NEGATIVE_SESSION_RETURN ||
            drawdownFromHigh(session) >= MAX_DRAWDOWN_FROM_HIGH ||
            belowVwap(session) || repeatedDrops(recent) >= MAX_SHARP_DROPS ||
            distributionBars(recent) >= MAX_DISTRIBUTION_BARS || lowerStructure(recent)
    }

    private fun ScanResult.asWatch(label: String): ScanResult =
        if (signalSource.contains("watch")) this else copy(signalSource = "$signalSource · $label")

    private fun isSharpDownsideWatch(result: ScanResult): Boolean =
        kotlin.math.abs(result.windowChangePercent) >= MIN_DOWNSIDE_WATCH_MOVE &&
            listOf(result.priceAnomaly, result.rangeAnomaly).filter(Double::isFinite)
                .maxOrNull()?.let { it >= MIN_DOWNSIDE_WATCH_Z } == true

    private fun sessionReturn(bars: List<MinuteBar>) = percent(bars.first().open, bars.last().close)

    private fun drawdownFromHigh(bars: List<MinuteBar>) =
        -percent(bars.maxOf(MinuteBar::high), bars.last().close)

    private fun belowVwap(bars: List<MinuteBar>): Boolean {
        val reliable = bars.filter { it.volumeStatus.isReliable && it.volume > 0.0 }
        if (reliable.size < MIN_VOLUME_BARS) return false
        val vwap = reliable.sumOf { ((it.high + it.low + it.close) / 3.0) * it.volume } /
            reliable.sumOf(MinuteBar::volume)
        return bars.last().close < vwap * (1.0 - MAX_BELOW_VWAP_PERCENT / 100.0)
    }

    private fun repeatedDrops(bars: List<MinuteBar>): Int {
        var lastDropEpoch = Long.MIN_VALUE
        var count = 0
        bars.windowed(DROP_WINDOW_BARS).forEach { window ->
            val end = window.last().minuteEpochSeconds
            if (percent(window.first().open, window.last().close) <= -SHARP_DROP_PERCENT &&
                end - lastDropEpoch >= DROP_WINDOW_BARS * 60L) {
                count++
                lastDropEpoch = end
            }
        }
        return count
    }

    private fun distributionBars(bars: List<MinuteBar>): Int {
        val volumes = bars.filter { it.volumeStatus.isReliable && it.volume > 0.0 }
            .map(MinuteBar::volume).sorted()
        if (volumes.size < MIN_VOLUME_BARS) return 0
        val medianVolume = volumes[volumes.size / 2]
        return bars.count { bar ->
            bar.volumeStatus.isReliable && bar.volume >= medianVolume * DISTRIBUTION_VOLUME_MULTIPLIER &&
                percent(bar.open, bar.close) <= -DISTRIBUTION_BODY_PERCENT
        }
    }

    private fun lowerStructure(bars: List<MinuteBar>): Boolean {
        val structure = bars.takeLast(STRUCTURE_MINUTES)
        if (structure.size < MIN_STRUCTURE_BARS) return false
        val middle = structure.size / 2
        val earlier = structure.take(middle)
        val later = structure.drop(middle)
        return later.maxOf(MinuteBar::high) < earlier.maxOf(MinuteBar::high) &&
            later.minOf(MinuteBar::low) < earlier.minOf(MinuteBar::low) &&
            percent(structure.first().open, structure.last().close) <= -LOWER_STRUCTURE_RETURN
    }

    private fun local(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds)
        .atZone(zoneOverride ?: MarketTimeZone.forSymbol(bar.symbol))

    private fun percent(first: Double, last: Double) = (last / first - 1.0) * 100.0

    private companion object {
        const val LOOKBACK_MINUTES = 120
        const val MIN_SESSION_BARS = 30
        const val MIN_RECENT_BARS = 30
        const val MAX_NEGATIVE_SESSION_RETURN = -0.50
        const val MAX_DRAWDOWN_FROM_HIGH = 1.50
        const val MAX_BELOW_VWAP_PERCENT = 0.35
        const val DROP_WINDOW_BARS = 5
        const val SHARP_DROP_PERCENT = 0.60
        const val MAX_SHARP_DROPS = 2
        const val DISTRIBUTION_VOLUME_MULTIPLIER = 1.50
        const val DISTRIBUTION_BODY_PERCENT = 0.25
        const val MAX_DISTRIBUTION_BARS = 2
        const val MIN_VOLUME_BARS = 20
        const val STRUCTURE_MINUTES = 60
        const val MIN_STRUCTURE_BARS = 40
        const val LOWER_STRUCTURE_RETURN = 0.35
        const val MIN_DOWNSIDE_WATCH_MOVE = 0.75
        const val MIN_DOWNSIDE_WATCH_Z = 4.5
    }
}
