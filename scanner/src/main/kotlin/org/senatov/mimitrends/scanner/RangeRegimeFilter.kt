package org.senatov.mimitrends.scanner

import org.senatov.mimitrends.model.MinuteBar
import kotlin.math.abs

internal object RangeRegimeFilter {
    private const val CONTEXT_MINUTES = 15
    private const val CONTEXT_BARS = 12
    private const val MIN_CONTEXT_BARS = 8
    private const val MAX_GAP_SECONDS = 180L
    private const val MIN_DIRECTIONAL_STEP_PERCENT = 0.01
    private const val MAX_RANGE_EFFICIENCY = 0.25
    private const val MIN_ALTERNATION_RATIO = 0.55
    private const val BREAKOUT_BUFFER_PERCENT = 0.05

    private fun percent(first: Double, last: Double): Double =
        if (first > 0.0) (last / first - 1.0) * 100.0 else 0.0

    private fun isContinuous(bars: List<MinuteBar>): Boolean {
        for (index in 1 until bars.size) {
            val gap = bars[index].minuteEpochSeconds - bars[index - 1].minuteEpochSeconds
            if (gap !in 1..MAX_GAP_SECONDS) return false
        }
        return true
    }

    fun blocks(bars: List<MinuteBar>, candidate: MinuteBar, direction: Int): Boolean {
        val eligibleContext = bars.filter { bar ->
            bar.minuteEpochSeconds < candidate.minuteEpochSeconds &&
                candidate.minuteEpochSeconds - bar.minuteEpochSeconds <= CONTEXT_MINUTES * 60L
        }
        val context: List<MinuteBar> = eligibleContext.takeLast(CONTEXT_BARS)
        if (context.size < MIN_CONTEXT_BARS || !isContinuous(context)) return false

        val changes = ArrayList<Double>(context.size - 1)
        for (index in 1 until context.size) {
            changes += percent(context[index - 1].close, context[index].close)
        }
        val path = changes.sumOf { change -> abs(change) }
        if (path <= 0.0) return false
        val displacement = abs(percent(context.first().close, context.last().close))
        val efficiency = displacement / path

        val directions = ArrayList<Int>(changes.size)
        for (change in changes) {
            if (change > MIN_DIRECTIONAL_STEP_PERCENT) directions += 1
            else if (change < -MIN_DIRECTIONAL_STEP_PERCENT) directions += -1
        }
        var directionChanges = 0
        for (index in 1 until directions.size) {
            if (directions[index] != directions[index - 1]) directionChanges++
        }
        val alternation = directionChanges.toDouble() / (directions.size - 1).coerceAtLeast(1)
        if (efficiency > MAX_RANGE_EFFICIENCY || alternation < MIN_ALTERNATION_RATIO) return false

        val breakoutBuffer = candidate.close * BREAKOUT_BUFFER_PERCENT / 100.0
        return if (direction > 0) {
            candidate.close <= context.maxOf { bar -> bar.high } + breakoutBuffer
        } else {
            candidate.close >= context.minOf { bar -> bar.low } - breakoutBuffer
        }
    }
}
