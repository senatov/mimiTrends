package org.senatov.mimitrends.charts

import org.senatov.mimitrends.model.MinuteBar
import java.text.DateFormat
import java.text.FieldPosition
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class ChartTimeline private constructor(
    val actualBars: List<MinuteBar>,
    val plottedBars: List<MinuteBar>,
    val isNonLinear: Boolean
) {
    init {
        require(actualBars.size == plottedBars.size)
    }

    fun displayMillis(actualEpochSeconds: Long): Double {
        if (!isNonLinear || actualBars.isEmpty()) return actualEpochSeconds * 1_000.0
        val index = actualBars.indices.minByOrNull {
            kotlin.math.abs(actualBars[it].minuteEpochSeconds - actualEpochSeconds)
        } ?: 0
        return plottedBars[index].minuteEpochSeconds * 1_000.0
    }

    fun actualBarAt(displayMillis: Double): MinuteBar? {
        val index = plottedBars.indices.minByOrNull {
            kotlin.math.abs(plottedBars[it].minuteEpochSeconds * 1_000.0 - displayMillis)
        } ?: return null
        return actualBars[index]
    }

    fun dateFormat(pattern: String): DateFormat = if (isNonLinear) {
        TimelineDateFormat(this, pattern)
    } else {
        SimpleDateFormat(pattern)
    }

    fun sessionBoundaries(): List<SessionBoundary> = actualBars.indices.drop(1).mapNotNull { index ->
        val previous = actualBars[index - 1]
        val current = actualBars[index]
        if (current.minuteEpochSeconds - previous.minuteEpochSeconds >= SESSION_GAP_SECONDS) {
            SessionBoundary(
                plottedBars[index].minuteEpochSeconds * 1_000.0,
                SHORT_DATE.format(Instant.ofEpochSecond(previous.minuteEpochSeconds)),
                SHORT_DATE.format(Instant.ofEpochSecond(current.minuteEpochSeconds))
            )
        } else {
            null
        }
    }

    data class SessionBoundary(val displayMillis: Double, val previousDate: String, val nextDate: String)

    private class TimelineDateFormat(
        @Transient private val timeline: ChartTimeline,
        pattern: String
    ) : DateFormat() {
        private val delegate = SimpleDateFormat(pattern)

        override fun format(date: Date, target: StringBuffer, fieldPosition: FieldPosition): StringBuffer {
            val actual = timeline.actualBarAt(date.time.toDouble())?.minuteEpochSeconds?.times(1_000L) ?: date.time
            return delegate.format(Date(actual), target, fieldPosition)
        }

        override fun parse(source: String, position: ParsePosition): Date? = delegate.parse(source, position)

        private companion object { const val serialVersionUID = 1L }
    }

    companion object {
        private const val CONTEXT_BARS = 180
        private const val DETAIL_BEFORE_SIGNAL = 12
        private const val DETAIL_AFTER_SIGNAL = 60
        private const val MIN_CONTEXT_SLOTS = 12
        private const val MAX_CONTEXT_SLOTS = 146
        private const val FUTURE_SLOTS = 60
        private const val MAX_REQUESTED_BARS = 24
        private const val MAX_EVENT_DISTANCE_SECONDS = 90L
        private const val DISPLAY_STEP_SECONDS = 60L
        private const val SESSION_GAP_SECONDS = 2 * 60 * 60L
        private val SHORT_DATE = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault())

        fun linear(bars: List<MinuteBar>): ChartTimeline {
            if (bars.size < 2) return ChartTimeline(bars, bars, false)
            val ordinarySteps = bars.zipWithNext { first, second ->
                second.minuteEpochSeconds - first.minuteEpochSeconds
            }.filter { it in 1 until SESSION_GAP_SECONDS }.sorted()
            val sessionStep = ordinarySteps.getOrNull(ordinarySteps.size / 2) ?: DISPLAY_STEP_SECONDS
            var displayEpoch = bars.first().minuteEpochSeconds
            var compressed = false
            val plotted = bars.mapIndexed { index, bar ->
                if (index > 0) {
                    val actualStep = bar.minuteEpochSeconds - bars[index - 1].minuteEpochSeconds
                    val displayStep = if (actualStep >= SESSION_GAP_SECONDS) sessionStep else actualStep
                    compressed = compressed || displayStep != actualStep
                    displayEpoch += displayStep
                }
                bar.copy(minuteEpochSeconds = displayEpoch)
            }
            return ChartTimeline(bars, plotted, compressed)
        }

        fun focused(
            bars: List<MinuteBar>,
            signalEpochSeconds: Long,
            includedEpochSeconds: Collection<Long> = emptyList()
        ): ChartTimeline {
            if (bars.isEmpty()) return linear(bars)
            val signalIndex = bars.indices.minByOrNull {
                kotlin.math.abs(bars[it].minuteEpochSeconds - signalEpochSeconds)
            } ?: bars.lastIndex
            val detailStart = (signalIndex - DETAIL_BEFORE_SIGNAL).coerceAtLeast(0)
            val detailEnd = (signalIndex + DETAIL_AFTER_SIGNAL + 1).coerceAtMost(bars.size)
            val requestedIndices = includedEpochSeconds.asSequence()
                .filter {
                    it in (bars.first().minuteEpochSeconds - MAX_EVENT_DISTANCE_SECONDS)..
                            (bars.last().minuteEpochSeconds + MAX_EVENT_DISTANCE_SECONDS)
                }
                .distinct()
                .sortedBy { kotlin.math.abs(it - signalEpochSeconds) }
                .take(MAX_REQUESTED_BARS)
                .mapNotNull { epoch ->
                    bars.indices.minByOrNull { kotlin.math.abs(bars[it].minuteEpochSeconds - epoch) }
                        ?.takeIf { kotlin.math.abs(bars[it].minuteEpochSeconds - epoch) <= MAX_EVENT_DISTANCE_SECONDS }
                }
                .filter { it !in detailStart until detailEnd }
                .toList()
            val contextStart = minOf(
                (detailStart - CONTEXT_BARS).coerceAtLeast(0),
                requestedIndices.filter { it < detailStart }.minOrNull() ?: detailStart
            )
            val detail = bars.subList(detailStart, detailEnd)
            val context = bars.subList(contextStart, detailStart)
            val future = bars.subList(detailEnd, bars.size)
            val contextSlots = (detail.size * 2).coerceIn(MIN_CONTEXT_SLOTS, MAX_CONTEXT_SLOTS)
            val previousSessionClose = previousSessionClose(bars, signalIndex, contextStart)
            val requestedBars = requestedIndices.map(bars::get)
            val reservedBars = (listOfNotNull(previousSessionClose, bars.last()) + requestedBars).distinct()
            val reservedSlots = reservedBars.count { it.minuteEpochSeconds < bars[detailStart].minuteEpochSeconds }
            val aggregateSlots = (contextSlots - reservedSlots).coerceAtLeast(1)
            val futureBars = if (future.isEmpty()) emptyList() else TrendChartSupport.aggregate(future, FUTURE_SLOTS)
            val selected = (reservedBars + TrendChartSupport.aggregate(context, aggregateSlots) + detail + futureBars)
                .distinctBy(MinuteBar::minuteEpochSeconds)
                .sortedBy(MinuteBar::minuteEpochSeconds)
            val displayStart = selected.first().minuteEpochSeconds
            val plotted = selected.mapIndexed { index, bar ->
                bar.copy(minuteEpochSeconds = displayStart + index * DISPLAY_STEP_SECONDS)
            }
            return ChartTimeline(selected, plotted, true)
        }

        private fun previousSessionClose(
            bars: List<MinuteBar>,
            signalIndex: Int,
            contextStart: Int
        ): MinuteBar? {
            val boundary = (1..signalIndex).lastOrNull { index ->
                bars[index].minuteEpochSeconds - bars[index - 1].minuteEpochSeconds >= SESSION_GAP_SECONDS
            } ?: return null
            val closeIndex = boundary - 1
            return bars[closeIndex].takeIf { closeIndex < contextStart }
        }
    }
}
