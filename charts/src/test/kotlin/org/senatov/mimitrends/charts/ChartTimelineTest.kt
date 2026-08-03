package org.senatov.mimitrends.charts

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import java.util.Date
import java.text.SimpleDateFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartTimelineTest {
    @Test fun `focus timeline gives the recent signal area at least one third of the chart`() {
        val bars = (0 until 300).map { index ->
            val epoch = if (index < 250) index * 60L else 86_400L + index * 60L
            MinuteBar("TEST", epoch, 100.0, 101.0, 99.0, 100.5, 1_000.0)
        }
        val signalEpoch = bars[290].minuteEpochSeconds

        val timeline = ChartTimeline.focused(bars, signalEpoch)

        val detailCount = timeline.actualBars.count { it.minuteEpochSeconds >= bars[278].minuteEpochSeconds }
        assertTrue(detailCount * 3 >= timeline.actualBars.size)
        assertTrue(timeline.plottedBars.zipWithNext().all { (a, b) -> b.minuteEpochSeconds - a.minuteEpochSeconds == 60L })
        assertEquals(bars.last(), timeline.actualBarAt(timeline.plottedBars.last().minuteEpochSeconds * 1_000.0))
    }

    @Test fun `non linear axis formats synthetic positions with real market time`() {
        val bars = listOf(
            MinuteBar("TEST", 0L, 100.0, 101.0, 99.0, 100.5, 1_000.0),
            MinuteBar("TEST", 86_400L, 100.5, 102.0, 100.0, 101.0, 2_000.0)
        )
        val timeline = ChartTimeline.focused(bars, bars.last().minuteEpochSeconds)

        val formatted = timeline.dateFormat("dd HH:mm").format(Date(timeline.displayMillis(86_400L).toLong()))

        assertEquals(SimpleDateFormat("dd HH:mm").format(Date(86_400_000L)), formatted)
    }
}
