package org.senatov.mimitrends

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveResultSelectorTest {
    @Test fun `adds at most two preferred adaptive candidates before the watch tier`() {
        val strict = listOf(result("STRICT", 8.0))
        val mild = (1..2).map { result("M$it", 6.0 - it, "Impulse ↑ · relaxed") }
        val balanced = (1..4).map { result("B$it", 4.0 - it / 10.0, "Trend ↑") }
        val broad = (1..10).map { result("W$it", 2.0 - it / 100.0, "Impulse ↑ · relaxed") }

        val selection = AdaptiveResultSelector.select(strict, listOf(mild, balanced, broad), 6, 15)

        assertEquals(3, selection.results.size)
        assertEquals(2, selection.adaptiveCount)
        assertTrue(selection.results.none { it.symbol.startsWith("W") })
    }

    @Test fun `never publishes more than fifteen rows`() {
        val strict = (1..30).map { result("S$it", it.toDouble()) }

        val selection = AdaptiveResultSelector.select(strict, emptyList(), 10, 50)

        assertEquals(15, selection.results.size)
        assertEquals("S30", selection.results.first().symbol)
    }

    @Test fun `does not fill the target with weak adaptive candidates`() {
        val strict = listOf(result("STRICT", 8.0))
        val weak = (1..12).map { result("W$it", 1.0 + it / 100.0, "Impulse ↑ · relaxed") }

        val selection = AdaptiveResultSelector.select(strict, listOf(weak), 10, 15)

        assertEquals(listOf("STRICT"), selection.results.map { it.symbol })
        assertEquals(0, selection.adaptiveCount)
    }

    @Test fun `publishes additional qualified trends as watch candidates`() {
        val trends = (1..4).map { index ->
            result("T$index", 3.6 - index / 10.0, "Steady rise ↑")
                .copy(signalWindowLabel = "30m steady", windowChangePercent = 0.6)
        }

        val selection = AdaptiveResultSelector.select(emptyList(), listOf(trends), 10, 15)

        assertEquals(4, selection.results.size)
        assertEquals(4, selection.adaptiveCount)
    }

    @Test fun `fills the list to at least seven with explicitly labelled watch candidates`() {
        val strict = listOf(result("STRICT", 8.0))
        val watch = (1..10).map { index ->
            result("W$index", 2.8 + index / 100.0, "Steady rise ↑")
                .copy(signalWindowLabel = "10m steady", windowChangePercent = 0.30, candleBodyRatio = 0.18)
        }

        val selection = AdaptiveResultSelector.select(strict, listOf(watch), 5, 15)

        assertEquals(7, selection.results.size)
        assertEquals(6, selection.results.count { it.signalSource.endsWith("· watch") })
    }

    private fun result(symbol: String, score: Double, source: String = "Impulse ↑") =
        TestScanResult.create(score, source, symbol)
}
