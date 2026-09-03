package org.senatov.mimitrends.charts

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import java.util.Date
import java.text.SimpleDateFormat
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartTimelineTest {
    @Test fun `linear timeline preserves exact event time between aggregated candles`() {
        val bars = listOf(
            MinuteBar("TEST", 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0),
            MinuteBar("TEST", 300L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        )

        assertEquals(180_000.0, ChartTimeline.linear(bars).displayMillis(180L))
    }

    @Test
    fun `linear axis formats its actual tick time inside a market closure`() {
        val bars = listOf(
            MinuteBar("TEST", 0L, 100.0, 101.0, 99.0, 100.0, 1_000.0),
            MinuteBar("TEST", 86_400L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        )
        val tickTime = 43_200_000L

        val formatted = ChartTimeline.linear(bars).dateFormat("dd HH:mm").format(Date(tickTime))

        assertEquals(SimpleDateFormat("dd HH:mm").format(Date(tickTime)), formatted)
    }

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

    @Test fun `focus timeline keeps previous session close outside recent context`() {
        val previousSession = (0 until 300).map { index ->
            MinuteBar("TEST", index * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }
        val currentSession = (0 until 240).map { index ->
            val price = 110.0 + index / 100.0
            MinuteBar("TEST", 86_400L + index * 60L, price, price + 1.0, price - 1.0, price, 1_000.0)
        }
        val bars = previousSession + currentSession

        val timeline = ChartTimeline.focused(bars, currentSession.last().minuteEpochSeconds)

        assertTrue(previousSession.last() in timeline.actualBars)
        assertTrue(timeline.actualBars.any { it.minuteEpochSeconds >= currentSession.first().minuteEpochSeconds })
    }

    @Test fun `focus timeline does not duplicate previous close already in recent context`() {
        val bars = listOf(
            MinuteBar("TEST", 0L, 100.0, 101.0, 99.0, 100.0, 1_000.0),
            MinuteBar("TEST", 86_400L, 110.0, 111.0, 109.0, 110.0, 1_000.0),
            MinuteBar("TEST", 86_460L, 111.0, 112.0, 110.0, 111.0, 1_000.0)
        )

        val timeline = ChartTimeline.focused(bars, bars.last().minuteEpochSeconds)

        assertEquals(1, timeline.actualBars.count { it.minuteEpochSeconds == 0L })
    }

    @Test fun `focus timeline preserves every explicitly requested earlier event candle`() {
        val bars = (0 until 1_000).map { index ->
            MinuteBar("TEST", index * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }
        val tradeEpochs = listOf(bars[100].minuteEpochSeconds, bars[450].minuteEpochSeconds)

        val timeline = ChartTimeline.focused(bars, bars[950].minuteEpochSeconds, tradeEpochs)

        assertTrue(tradeEpochs.all { epoch -> timeline.actualBars.any { it.minuteEpochSeconds == epoch } })
        val detailCount = timeline.actualBars.count { it.minuteEpochSeconds >= bars[938].minuteEpochSeconds }
        assertTrue(detailCount * 3 >= timeline.actualBars.size)
    }

    @Test
    fun `focus timeline remains bounded when the signal is far in the past`() {
        val bars = (0 until 10_000).map { index ->
            MinuteBar("TEST", index * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }

        val timeline = ChartTimeline.focused(bars, bars[100].minuteEpochSeconds)

        assertTrue(timeline.actualBars.size <= 320)
        assertTrue(bars[100] in timeline.actualBars)
        assertTrue(bars.last() in timeline.actualBars)
    }

    @Test
    fun `focus timeline ignores requested events far outside loaded data`() {
        val bars = (0 until 300).map { index ->
            MinuteBar("TEST", index * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }

        val timeline = ChartTimeline.focused(
            bars,
            bars[200].minuteEpochSeconds,
            listOf(-86_400L, bars.last().minuteEpochSeconds + 86_400L)
        )

        assertTrue(timeline.actualBars.size <= 320)
        assertTrue(bars.first() !in timeline.actualBars)
    }
}