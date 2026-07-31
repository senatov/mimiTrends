package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.db.MarketRepository
import org.senatov.mimitrends.log.LogTag
import org.senatov.mimitrends.model.*
import org.slf4j.LoggerFactory
import java.time.*

class ScannerEngine(private val repository: MarketRepository, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val current = mutableMapOf<String, MinuteBar>()
    private val lastEvaluationMillis = mutableMapOf<String, Long>()

    @Synchronized fun accept(tick: TradeTick, criteria: ScannerCriteria): ScanResult? {
        log.trace(LogTag.DB, "accept(symbol={}, price={}, volume={})", tick.symbol, tick.price, tick.volume)
        val minute = tick.timestampMillis / 60_000 * 60
        val old = current[tick.symbol]
        val bar = if (old?.minuteEpochSeconds == minute) old.copy(high = maxOf(old.high, tick.price), low = minOf(old.low, tick.price), close = tick.price, volume = old.volume + tick.volume)
        else MinuteBar(tick.symbol, minute, tick.price, tick.price, tick.price, tick.price, tick.volume)
        current[tick.symbol] = bar
        if (tick.timestampMillis - (lastEvaluationMillis[tick.symbol] ?: 0) < 1_000) return null
        lastEvaluationMillis[tick.symbol] = tick.timestampMillis
        repository.upsertMinuteBar(bar)
        return evaluate(tick.symbol, tick.timestampMillis, criteria)
    }

    fun evaluate(symbol: String, nowMillis: Long, criteria: ScannerCriteria): ScanResult {
        log.debug(LogTag.DB, "evaluate(symbol={})", symbol)
        val now = Instant.ofEpochMilli(nowMillis)
        val from = now.minus(Duration.ofDays((criteria.baselineSessions * 3L).coerceAtLeast(30))).epochSecond
        val bars = repository.loadMinuteBars(symbol, from)
        val latest = bars.lastOrNull() ?: return ScanResult(symbol, 0.0, null, null, null, 0.0, false, nowMillis)
        val oneClose = bars.lastOrNull { it.minuteEpochSeconds < latest.minuteEpochSeconds }?.close
        val fiveClose = bars.lastOrNull { it.minuteEpochSeconds <= latest.minuteEpochSeconds - 300 }?.close
        val change1 = oneClose?.takeIf { it > 0 }?.let { (latest.close / it - 1) * 100 }
        val change5 = fiveClose?.takeIf { it > 0 }?.let { (latest.close / it - 1) * 100 }
        val today = LocalDateTime.ofInstant(now, zone).toLocalDate()
        val grouped = bars.groupBy { Instant.ofEpochSecond(it.minuteEpochSeconds).atZone(zone).toLocalDate() }
        val sessionVolume = grouped[today].orEmpty().sumOf { it.volume }
        val currentMinuteOfDay = Instant.ofEpochSecond(latest.minuteEpochSeconds).atZone(zone).toLocalTime().toSecondOfDay() / 60
        val baselines = grouped.entries.asSequence().filter { it.key < today }.sortedByDescending { it.key }.take(criteria.baselineSessions).map { (_, dayBars) ->
            dayBars.filter { Instant.ofEpochSecond(it.minuteEpochSeconds).atZone(zone).toLocalTime().toSecondOfDay() / 60 <= currentMinuteOfDay }.sumOf { it.volume }
        }.filter { it > 0 }.toList()
        val relative = baselines.takeIf { it.size >= minOf(3, criteria.baselineSessions) }?.let { sessionVolume / it.average() }
        val relativeVolumePasses = criteria.minRelativeVolume <= 0 || relative != null && relative > criteria.minRelativeVolume
        val matches = relativeVolumePasses && change1 != null && change5 != null &&
            change1 > criteria.minChange1mPercent && change5 > criteria.minChange5mPercent && latest.close > criteria.minPrice && sessionVolume > criteria.minSessionVolume
        return ScanResult(symbol, latest.close, relative, change1, change5, sessionVolume, matches, nowMillis)
    }
}
