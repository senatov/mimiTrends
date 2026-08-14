package org.senatov.mimitrends.charts

import org.senatov.mimitrends.model.MinuteBar
import java.text.DateFormat
import java.text.FieldPosition
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date

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

    fun dateFormat(pattern: String): DateFormat = TimelineDateFormat(this, pattern)

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
        private const val MIN_CONTEXT_SLOTS = 12
        private const val DISPLAY_STEP_SECONDS = 60L
        private const val SESSION_GAP_SECONDS = 2 * 60 * 60L

        fun linear(bars: List<MinuteBar>): ChartTimeline = ChartTimeline(bars, bars, false)

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
            val requestedIndices = includedEpochSeconds.mapNotNull { epoch ->
                bars.indices.minByOrNull { kotlin.math.abs(bars[it].minuteEpochSeconds - epoch) }
            }.filter { it < detailStart }.distinct()
            val contextStart = minOf(
                (detailStart - CONTEXT_BARS).coerceAtLeast(0),
                requestedIndices.minOrNull() ?: detailStart
            )
            val detail = bars.subList(detailStart, bars.size)
            val context = bars.subList(contextStart, detailStart)
            val contextSlots = (detail.size * 2).coerceAtLeast(MIN_CONTEXT_SLOTS)
            val previousSessionClose = previousSessionClose(bars, signalIndex, contextStart)
            val requestedBars = requestedIndices.map(bars::get)
            val reservedBars = (listOfNotNull(previousSessionClose) + requestedBars).distinct()
            val reservedSlots = reservedBars.size
            val aggregateSlots = (contextSlots - reservedSlots).coerceAtLeast(1)
            val selected = (reservedBars + TrendChartSupport.aggregate(context, aggregateSlots) + detail)
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
