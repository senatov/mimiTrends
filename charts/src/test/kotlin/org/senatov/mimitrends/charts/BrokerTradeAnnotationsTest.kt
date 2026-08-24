package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.XYShapeAnnotation
import org.jfree.chart.annotations.XYTextAnnotation
import org.jfree.chart.plot.XYPlot
import org.junit.jupiter.api.Test
import org.senatov.mimitrends.model.BrokerTrade
import org.senatov.mimitrends.model.MinuteBar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrokerTradeAnnotationsTest {
    @Test fun `renders a trade highlight and clears only trade annotations`() {
        val plot = XYPlot()
        val foreign = XYTextAnnotation("signal", 0.0, 0.0)
        plot.addAnnotation(foreign)
        val renderer = BrokerTradeAnnotations(plot)
        val bars = (0L..10L).map { minute ->
            MinuteBar("TEST", minute * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }
        val trade = BrokerTrade(
            "TEST", null, 1.0, 120L, 100.0, 480L, 101.0,
            1.0, 1.0, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 1.0)

        assertTrue(plot.annotations.any { it is XYShapeAnnotation })
        renderer.clear()
        assertEquals(listOf(foreign), plot.annotations)
    }

    @Test fun `clears the previous instrument cards before a replacement render`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val bars = (0L..10L).map { minute ->
            MinuteBar("TEST", minute * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }
        val trade = BrokerTrade(
            "SAP.DE", null, 1.0, 120L, 100.0, null, null,
            null, null, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 1.0)
        assertEquals(1, renderer.renderedCardBounds().size)

        renderer.clear()

        assertTrue(renderer.renderedCardBounds().isEmpty())
        assertTrue(renderer.renderedTradePoints().isEmpty())
    }

    @Test fun `open position renders only its entry without a fabricated exit range`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val bars = (0L..10L).map { minute ->
            MinuteBar("TEST", minute * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }
        val trade = BrokerTrade(
            "TEST", null, 1.0, 120L, 100.0, null, null,
            null, null, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 1.0)

        assertEquals(listOf(BrokerTradeAnnotations.TradePoint(120_000.0, 100.0)),
            renderer.renderedTradePoints())
        assertEquals(1, renderer.renderedCardBounds().size)
    }

    @Test fun `keeps a large trade card inside the visible chart edges`() {
        val renderer = BrokerTradeAnnotations(XYPlot())

        val bounds = renderer.cardBounds(
            preferredX = 0.0,
            preferredBottom = 109.0,
            timeStep = 60_000.0,
            priceSpan = 10.0,
            domainMin = 0.0,
            domainMax = 1_200_000.0,
            rangeMin = 100.0,
            rangeMax = 110.0
        )

        assertTrue(bounds.left >= 0.0)
        assertTrue(bounds.right <= 1_200_000.0)
        assertTrue(bounds.bottom >= 100.0)
        assertTrue(bounds.top <= 110.0)
    }

    @Test fun `places card above candles and keeps dragged position across history render`() {
        val plot = XYPlot()
        val renderer = BrokerTradeAnnotations(plot)
        val bars = (0L..20L).map { minute ->
            MinuteBar("TEST", minute * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }
        val trade = BrokerTrade(
            "TEST", null, 1.0, 120L, 100.0, 480L, 101.0,
            1.0, 1.0, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 1.0)
        val initial = renderer.renderedCardBounds().single()
        assertTrue(initial.bottom > bars.maxOf(MinuteBar::high))
        assertTrue(renderer.beginDrag(initial.centerX, initial.bottom + initial.height / 2.0))
        renderer.dragTo(initial.centerX + 120_000.0, initial.bottom + initial.height / 2.0)
        renderer.endDrag()
        val dragged = renderer.renderedCardBounds().single()
        assertTrue(dragged.centerX > initial.centerX)

        renderer.render(listOf(trade), bars, bars.filterIndexed { index, _ -> index % 2 == 0 }, 1.0)

        assertTrue(renderer.renderedCardBounds().single().centerX > initial.centerX)
        assertEquals(8, plot.annotations.count { it is XYShapeAnnotation })
        assertEquals(1, plot.annotations.count { it is TradeCardAnnotation })
    }

    @Test fun `does not convert trades that already use display currency`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val bars = (0L..10L).map { minute ->
            MinuteBar("COP", minute * 60L, 116.0, 119.0, 114.0, 117.0, 1_000.0)
        }
        val trade = BrokerTrade(
            "COP", null, 1.0, 120L, 103.20, 480L, 103.80,
            0.60, 0.58, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 0.875)

        assertEquals(listOf(103.20, 103.80), renderer.renderedTradePoints().map { it.y })
    }

    @Test fun `anchors a trade card to the nearest plotted candle point`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val card = BrokerTradeAnnotations.CardBounds(0.0, 110.0, 600_000.0, 114.0)
        val entryCandle = BrokerTradeAnnotations.TradePoint(120_000.0, 100.0)
        val exitCandle = BrokerTradeAnnotations.TradePoint(1_200_000.0, 102.0)

        val anchor = renderer.connectorAnchor(
            listOf(entryCandle, exitCandle), card, timeStep = 60_000.0, priceSpan = 10.0
        )

        assertEquals(entryCandle, anchor)
        assertTrue(anchor.y > 99.0, "connector must terminate on the candle, not the chart floor")
    }

    @Test fun `does not invent an entry candle for a trade opened before the visible range`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val bars = (10L..20L).map { minute ->
            MinuteBar("TEST", minute * 60L, 32.0, 32.5, 31.5, 32.2, 1_000.0)
        }
        val trade = BrokerTrade(
            "TEST", null, 1.0, 60L, 27.15, 1_200L, 32.4,
            5.25, 19.34, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 1.0)

        assertEquals(emptyList(), renderer.renderedTradePoints())
        assertEquals(emptyList(), renderer.renderedCardBounds())
    }

    @Test fun `keeps actual execution price when it is outside candle OHLC`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val bars = (0L..10L).map { minute ->
            MinuteBar("TEST", minute * 60L, 31.0, 31.4, 30.8, 31.1, 1_000.0)
        }
        val trade = BrokerTrade(
            "TEST", null, 1.0, 0L, 27.15, 600L, 31.1,
            3.95, 14.55, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 1.0)

        assertEquals(27.15, renderer.renderedTradePoints().first().y, 0.000_001)
    }

    @Test fun `does not relocate a trade to a distant retained candle`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val bars = listOf(
            MinuteBar("TEST", 0L, 31.0, 31.4, 30.8, 31.1, 1_000.0),
            MinuteBar("TEST", 600L, 31.0, 31.4, 30.8, 31.1, 1_000.0)
        )
        val trade = BrokerTrade(
            "TEST", null, 1.0, 300L, 31.1, null, null,
            null, null, 0.0, "EUR"
        )

        renderer.render(listOf(trade), bars, bars, 1.0)

        assertEquals(emptyList(), renderer.renderedTradePoints())
        assertEquals(emptyList(), renderer.renderedCardBounds())
    }

    @Test fun `keeps nearby trade cards readable and separated on a long range`() {
        val renderer = BrokerTradeAnnotations(XYPlot())
        val bars = (0L..1_000L).map { minute ->
            MinuteBar("TEST", minute * 60L, 100.0, 101.0, 99.0, 100.0, 1_000.0)
        }
        val trades = listOf(
            BrokerTrade("TEST", null, 1.0, 120L, 100.0, 480L, 101.0, 1.0, 1.0, 0.0, "EUR"),
            BrokerTrade("TEST", null, 1.0, 180L, 100.0, 540L, 101.0, 1.0, 1.0, 0.0, "EUR")
        )

        renderer.render(trades, bars, bars, 1.0)

        val cards = renderer.renderedCardBounds()
        assertEquals(2, cards.size)
        assertTrue(cards.all { it.width >= 0.15 * 60_000_000.0 })
        assertTrue(cards[0].right <= cards[1].left || cards[1].right <= cards[0].left ||
            cards[0].top <= cards[1].bottom || cards[1].top <= cards[0].bottom)
    }

    @Test fun `stacks colliding cards downward when preferred position is at the ceiling`() {
        val first = TradeCardLayout.place(
            preferredX = 90.0, preferredBottom = 109.8, timeStep = 10.0, priceSpan = 10.0,
            domainMin = 0.0, domainMax = 180.0, rangeMin = 100.0, rangeMax = 112.0,
            occupied = emptyList()
        )

        val second = TradeCardLayout.place(
            preferredX = 90.0, preferredBottom = 109.8, timeStep = 10.0, priceSpan = 10.0,
            domainMin = 0.0, domainMax = 180.0, rangeMin = 100.0, rangeMax = 112.0,
            occupied = listOf(first)
        )

        assertTrue(second.top <= first.bottom)
        assertTrue(second.bottom >= 100.0)
        assertTrue(first.top <= 112.0)
    }
}
