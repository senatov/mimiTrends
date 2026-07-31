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
        log.debug(LogTag.DB, "evaluate(symbol={}, bars={}, window={})", symbol, bars.size, criteria.anomalyWindow)
        val sorted = bars.sortedBy { it.minuteEpochSeconds }
        val latest = sorted.lastOrNull() ?: return null
        val current = currentWindow(sorted, criteria.anomalyWindow)
        if (current.size < 2) return null
        val currentReturn = percent(current.first().open, current.last().close)
        val currentVolume = current.sumOf { it.volume }
        val references = referenceWindows(sorted, current, criteria.anomalyWindow)
        val referenceReturns = references.mapNotNull { sample ->
            sample.takeIf { it.size >= 2 }?.let { abs(percent(it.first().open, it.last().close)) }
        }
        val referenceVolumes = references.map { it.sumOf(MinuteBar::volume) }.filter { it > 0 }
        val normalMove = median(referenceReturns).takeIf { it > 0 } ?: abs(currentReturn).coerceAtLeast(0.01)
        val normalVolume = median(referenceVolumes).takeIf { it > 0 } ?: currentVolume.coerceAtLeast(1.0)
        val priceAnomaly = abs(currentReturn) / normalMove
        val volumeAnomaly = currentVolume / normalVolume
        val sessionBars = sameSession(sorted, latest)
        val sessionVolume = sessionBars.sumOf { it.volume }
        val turnover = sessionBars.sumOf { it.close * it.volume }
        if (latest.close < criteria.minPrice || turnover < criteria.minSessionTurnover) return null
        return ScanResult(
            symbol = symbol,
            price = latest.close,
            anomalyScore = max(priceAnomaly, volumeAnomaly),
            priceAnomaly = priceAnomaly,
            volumeAnomaly = volumeAnomaly,
            windowChangePercent = currentReturn,
            windowVolume = currentVolume,
            sessionVolume = sessionVolume,
            sessionTurnover = turnover,
            updatedAtMillis = latest.minuteEpochSeconds * 1_000
        )
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
        val historical = bars.filter { it.minuteEpochSeconds < currentStart }
        if (historical.isEmpty()) return emptyList()
        val samples = mutableListOf<List<MinuteBar>>()
        var end = historical.last().minuteEpochSeconds
        while (samples.size < 60 && end >= historical.first().minuteEpochSeconds) {
            val sample = historical.filter { it.minuteEpochSeconds >= end - seconds && it.minuteEpochSeconds <= end }
            if (sample.size >= 2) samples += sample
            end -= seconds
        }
        return samples
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
}
