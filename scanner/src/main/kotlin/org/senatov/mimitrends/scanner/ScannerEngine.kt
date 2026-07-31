package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.AnomalyWindow
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import org.senatov.mimitrends.model.ScannerCriteria
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

class ScannerEngine(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun evaluate(symbol: String, bars: List<MinuteBar>, criteria: ScannerCriteria): ScanResult? {
        log.debug(LogTag.DB, "evaluate(symbol={}, bars={}, recentWindow=15m)", symbol, bars.size)
        val sorted = bars.sortedBy { it.minuteEpochSeconds }
        val latest = sorted.lastOrNull() ?: return null
        val signal = recentSignals(sorted, criteria.baselineSessions).maxByOrNull(Signal::weightedScore) ?: return null
        val sessionBars = sameSession(sorted, latest)
        val sessionVolume = sessionBars.sumOf { it.volume }
        val turnover = sessionBars.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = signal.weightedScore,
            priceAnomaly = signal.priceAnomaly,
            volumeAnomaly = signal.volumeAnomaly,
            windowChangePercent = signal.changePercent,
            windowVolume = signal.volume,
            sessionVolume = sessionVolume,
            sessionTurnover = turnover,
            signalAgeMinutes = signal.ageMinutes,
            signalSource = if (signal.priceAnomaly >= signal.volumeAnomaly) {
                if (signal.changePercent >= 0) "Price ↑" else "Price ↓"
            } else "Volume",
            updatedAtMillis = latest.minuteEpochSeconds * 1_000
        )
    }

    private fun recentSignals(bars: List<MinuteBar>, baselineSessions: Int): List<Signal> {
        log.trace(LogTag.DB, "recentSignals(bars={}, baselineSessions={})", bars.size, baselineSessions)
        val latest = bars.last()
        val historicalCutoff = latest.minuteEpochSeconds - 15 * 60
        val references = fixedReferenceWindows(
            bars, historicalCutoff, SIGNAL_SECONDS,
            maxSamples = baselineSessions.coerceIn(3, 20) * WINDOWS_PER_SESSION
        )
        return listOf(0, 5, 10).mapNotNull { ageMinutes ->
            val end = latest.minuteEpochSeconds - ageMinutes * 60L
            val start = end - SIGNAL_SECONDS
            val sample = bars.filter { it.minuteEpochSeconds > start && it.minuteEpochSeconds <= end }
            if (sample.size < 2) return@mapNotNull null
            val change = percent(sample.first().open, sample.last().close)
            val volume = sample.sumOf(MinuteBar::volume)
            val normalMove = median(references.map { abs(percent(it.first().open, it.last().close)) })
                .takeIf { it > 0 } ?: MIN_NORMAL_MOVE_PERCENT
            val normalVolume = median(references.map { it.sumOf(MinuteBar::volume) }.filter { it > 0 })
                .takeIf { it > 0 } ?: volume.coerceAtLeast(1.0)
            val priceAnomaly = abs(change) / normalMove
            val volumeAnomaly = volume / normalVolume
            val recencyWeight = when (ageMinutes) { 0 -> 1.0; 5 -> 0.88; else -> 0.74 }
            Signal(ageMinutes, change, volume, priceAnomaly, volumeAnomaly,
                max(priceAnomaly, volumeAnomaly) * recencyWeight)
        }
    }

    private fun fixedReferenceWindows(
        bars: List<MinuteBar>,
        currentStart: Long,
        seconds: Long,
        maxSamples: Int = 60
    ): List<List<MinuteBar>> {
        return bars.asSequence()
            .filter { it.minuteEpochSeconds < currentStart }
            .groupBy { it.minuteEpochSeconds / seconds }
            .toSortedMap()
            .values
            .filter { it.size >= 2 }
            .takeLast(maxSamples)
    }

    private fun currentWindow(bars: List<MinuteBar>, window: AnomalyWindow): List<MinuteBar> {
        log.trace(LogTag.DB, "currentWindow(bars={}, window={})", bars.size, window)
        val latest = bars.last()
        return if (window == AnomalyWindow.SESSION) sameSession(bars, latest) else {
            val from = latest.minuteEpochSeconds - requireNotNull(window.seconds)
            bars.filter { it.minuteEpochSeconds >= from }
        }
    }

    private fun referenceWindows(
        bars: List<MinuteBar>,
        current: List<MinuteBar>,
        window: AnomalyWindow
    ): List<List<MinuteBar>> {
        log.trace(LogTag.DB, "referenceWindows(bars={}, current={}, window={})", bars.size, current.size, window)
        val currentStart = current.first().minuteEpochSeconds
        if (window == AnomalyWindow.SESSION) {
            val currentDate = date(current.last())
            val elapsedTime = Instant.ofEpochSecond(current.last().minuteEpochSeconds).atZone(zone).toLocalTime()
            return bars.filter { bar ->
                date(bar) < currentDate && Instant.ofEpochSecond(bar.minuteEpochSeconds).atZone(zone).toLocalTime() <= elapsedTime
            }.groupBy(::date).values.toList()
        }
        val seconds = requireNotNull(window.seconds)
        return fixedReferenceWindows(bars, currentStart, seconds)
    }

    private fun sameSession(bars: List<MinuteBar>, latest: MinuteBar): List<MinuteBar> {
        log.trace(LogTag.DB, "sameSession(bars={}, symbol={})", bars.size, latest.symbol)
        val target = date(latest)
        return bars.filter { date(it) == target }
    }

    private fun date(bar: MinuteBar) = Instant.ofEpochSecond(bar.minuteEpochSeconds).atZone(zone).toLocalDate()

    private fun percent(open: Double, close: Double): Double = if (open > 0) (close / open - 1.0) * 100.0 else 0.0

    private fun median(values: List<Double>): Double {
        log.trace(LogTag.DB, "median(values={})", values.size)
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private data class Signal(
        val ageMinutes: Int,
        val changePercent: Double,
        val volume: Double,
        val priceAnomaly: Double,
        val volumeAnomaly: Double,
        val weightedScore: Double
    )

    private companion object {
        const val SIGNAL_SECONDS = 5 * 60L
        const val WINDOWS_PER_SESSION = 100
        const val MIN_NORMAL_MOVE_PERCENT = 0.01
    }
}
