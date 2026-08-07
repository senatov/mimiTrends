package org.senatov.mimitrends.charts

import javafx.scene.input.MouseEvent
import org.jfree.chart.axis.DateAxis
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.fx.ChartViewer
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.ui.RectangleEdge

internal class TradeAnnotationDragController(
    viewer: ChartViewer,
    private val dateAxis: DateAxis,
    private val priceAxis: NumberAxis,
    private val pricePlot: XYPlot,
    private val annotations: BrokerTradeAnnotations
) {
    private var dragging = false

    init {
        viewer.canvas.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            val point = chartPoint(viewer, event, requireInside = true) ?: return@addEventFilter
            dragging = annotations.beginDrag(point.first, point.second)
            if (dragging) event.consume()
        }
        viewer.canvas.addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
            if (!dragging) return@addEventFilter
            chartPoint(viewer, event, requireInside = false)?.let { annotations.dragTo(it.first, it.second) }
            event.consume()
        }
        viewer.canvas.addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            if (!dragging) return@addEventFilter
            dragging = false
            annotations.endDrag()
            event.consume()
        }
    }

    private fun chartPoint(
        viewer: ChartViewer,
        event: MouseEvent,
        requireInside: Boolean
    ): Pair<Double, Double>? {
        val plotInfo = viewer.canvas.renderingInfo.plotInfo
        if (plotInfo.subplotCount < 1) return null
        val area = plotInfo.getSubplotInfo(0).dataArea
        if (requireInside && !area.contains(event.x, event.y)) return null
        val domain = dateAxis.java2DToValue(event.x, area, RectangleEdge.BOTTOM)
        val range = priceAxis.java2DToValue(event.y, area, pricePlot.rangeAxisEdge)
        return if (domain.isFinite() && range.isFinite()) domain to range else null
    }
}
