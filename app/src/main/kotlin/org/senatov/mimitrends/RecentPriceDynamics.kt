package org.senatov.mimitrends

import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult

internal object RecentPriceDynamics {
    fun apply(result: ScanResult, bars: List<MinuteBar>): ScanResult {
        val currentSession = continuousTail(bars)
        return result.copy(
            recentThreeMinutePercent = returnPercent(currentSession, 3),
            recentFiveMinutePercent = returnPercent(currentSession, 5),
            recentDirectionChanges = directionChanges(currentSession.takeLast(6))
        )
    }

    private fun continuousTail(bars: List<MinuteBar>): List<MinuteBar> {
        val sorted = bars.sortedBy(MinuteBar::minuteEpochSeconds)
        if (sorted.isEmpty()) return emptyList()
        return sorted.asReversed().zipWithNext().takeWhile { (later, earlier) ->
            later.minuteEpochSeconds - earlier.minuteEpochSeconds == 60L
        }.flatMap { listOf(it.first, it.second) }.distinctBy(MinuteBar::minuteEpochSeconds).asReversed()
    }

    private fun returnPercent(bars: List<MinuteBar>, minutes: Int): Double {
        if (bars.size <= minutes) return Double.NaN
        val first = bars[bars.lastIndex - minutes].close
        return if (first > 0.0) (bars.last().close / first - 1.0) * 100.0 else Double.NaN
    }

    private fun directionChanges(bars: List<MinuteBar>): Int = bars.zipWithNext { first, second ->
        (second.close - first.close).compareTo(0.0)
    }.filter { it != 0 }.zipWithNext().count { (first, second) -> first != second }
}
