package org.senatov.mimitrends.scanner

import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.MinuteBar
import org.senatov.mimitrends.model.ScanResult
import java.time.ZoneId
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryTimingClassifierTest {
    private val classifier = EntryTimingClassifier(ZoneId.of("UTC"))

    @Test fun `marks a mature rise that stalls at its high as extended`() {
        val bars = risingBars(35, 0.04).toMutableList()
        var minute = bars.size.toLong()
        repeat(5) { bars += candle(minute++, bars.last().close) }

        val classified = classifier.classify(bars, trendResult())

        assertTrue(classified.signalSource.contains("extended · wait for pullback"))
    }

    @Test fun `keeps an orderly current rise active`() {
        val classified = classifier.classify(risingBars(40, 0.03), trendResult())

        assertFalse(classified.signalSource.contains("wait for pullback"))
    }

    private fun risingBars(count: Int, step: Double): List<MinuteBar> {
        var price = 100.0
        return (0 until count).map { minute ->
            price += step
            candle(minute.toLong(), price)
        }
    }

    private fun candle(minute: Long, close: Double) = MinuteBar(
        "TEST", minute * 60L, close - 0.01, close + 0.02, close - 0.02, close, 1_000.0
    )

    private fun trendResult() = ScanResult(
        symbol = "TEST", price = 101.0, anomalyScore = 4.0, priceAnomaly = Double.NaN,
        volumeAnomaly = Double.NaN, rangeAnomaly = Double.NaN, relativeVolume = Double.NaN,
        candleBodyRatio = 0.6, windowChangePercent = 1.0, windowVolume = 40_000.0,
        sessionVolume = 40_000.0, sessionTurnover = 4_000_000.0, signalAgeMinutes = 0,
        signalSource = "Steady rise ↑", updatedAtMillis = 0L, signalWindowLabel = "40m steady"
    )
}
