package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.XYBoxAnnotation
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
        assertEquals(1, plot.annotations.count { it is XYBoxAnnotation })
        assertEquals(2, plot.annotations.count { it is XYTextAnnotation })
    }
}
