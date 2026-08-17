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

        assertEquals(2, selection.results.size)
        assertEquals(1, selection.adaptiveCount)
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

    @Test fun `does not relabel additional qualified trends as watch candidates`() {
        val trends = (1..4).map { index ->
            result("T$index", 3.6 - index / 10.0, "Steady rise ↑")
                .copy(signalWindowLabel = "30m steady", windowChangePercent = 0.6)
        }

        val selection = AdaptiveResultSelector.select(emptyList(), listOf(trends), 10, 15)

        assertEquals(2, selection.results.size)
        assertTrue(selection.results.none { it.signalSource.contains("watch") })
    }

    @Test fun `does not fill the list by converting weaker trends to watch candidates`() {
        val strict = listOf(result("STRICT", 8.0))
        val watch = (1..10).map { index ->
            result("W$index", 2.8 + index / 100.0, "Steady rise ↑")
                .copy(signalWindowLabel = "10m steady", windowChangePercent = 0.30, candleBodyRatio = 0.18)
        }

        val selection = AdaptiveResultSelector.select(strict, listOf(watch), 5, 15)

        assertEquals(listOf("STRICT"), selection.results.map { it.symbol })
    }

    @Test fun `can fill the list with candidates already classified as watch`() {
        val strict = listOf(result("STRICT", 8.0))
        val watch = (1..10).map { index ->
            result("W$index", 4.0 + index / 100.0, "Steady rise ↑ · watch")
                .copy(signalWindowLabel = "20m steady", windowChangePercent = 0.60, candleBodyRatio = 0.30)
        }

        val selection = AdaptiveResultSelector.select(strict, listOf(watch), 5, 15)

        assertEquals(7, selection.results.size)
        assertEquals(6, selection.results.count { it.signalSource.endsWith("· watch") })
    }

    @Test fun `fills quiet markets with the best long term rises`() {
        val longTerm = (1..10).map { index ->
            result("L$index", 4.0 - index / 10.0, "Steady rise ↑ · long-term")
                .copy(signalWindowLabel = "120m steady", windowChangePercent = 1.2, candleBodyRatio = 0.35)
        }

        val selection = AdaptiveResultSelector.select(
            strict = emptyList(), fallbackLevels = emptyList(), requestedTarget = 7,
            requestedLimit = 15, longTerm = longTerm
        )

        assertEquals(7, selection.results.size)
        assertTrue(selection.results.all { it.signalSource.contains("long-term") })
    }

    @Test fun `fills a quiet market with explicitly labelled neutral contexts`() {
        val contexts = (1..10).map { index ->
            result("C$index", 3.0 + index / 100.0, "Neutral context · watch")
                .copy(signalWindowLabel = "30m context", windowChangePercent = 0.02)
        }

        val selection = AdaptiveResultSelector.select(
            strict = emptyList(), fallbackLevels = emptyList(), requestedTarget = 7,
            requestedLimit = 15, contexts = contexts
        )

        assertEquals(7, selection.results.size)
        assertTrue(selection.results.all { it.signalSource.startsWith("Neutral context") })
    }

    private fun result(symbol: String, score: Double, source: String = "Impulse ↑") =
        TestScanResult.create(score, source, symbol)
}
