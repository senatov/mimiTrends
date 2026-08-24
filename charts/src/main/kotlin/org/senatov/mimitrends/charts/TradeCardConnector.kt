package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.XYAnnotation
import java.awt.BasicStroke
import java.awt.Color
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

internal object TradeCardConnector {
    fun create(
        trade: Bounds,
        card: Bounds,
        domainUnit: Double,
        rangeUnit: Double
    ): List<XYAnnotation> {
        val geometry = geometry(trade, card, domainUnit, rangeUnit)
        val path = Path2D.Double().apply {
            moveTo(geometry.start.x, geometry.start.y)
            curveTo(
                geometry.control1.x,
                geometry.control1.y,
                geometry.control2.x,
                geometry.control2.y,
                geometry.end.x,
                geometry.end.y
            )
        }
        return buildList {
            add(BrokerTradeShapeAnnotation(path, CONNECTOR_STROKE, CONNECTOR_COLOR))
            addAll(fastener(geometry.start, geometry.domainUnit, geometry.rangeUnit))
            addAll(fastener(geometry.end, geometry.domainUnit, geometry.rangeUnit))
        }
    }

    internal fun geometry(
        trade: Bounds,
        card: Bounds,
        domainUnit: Double,
        rangeUnit: Double
    ): Geometry {
        val safeDomainUnit = domainUnit.coerceAtLeast(1.0)
        val safeRangeUnit = rangeUnit.coerceAtLeast(0.000_001)
        val start = edgePoint(trade, card.centerX, card.centerY, safeDomainUnit, safeRangeUnit)
        val end = edgePoint(card, trade.centerX, trade.centerY, safeDomainUnit, safeRangeUnit)
        val dx = (end.x - start.x) / safeDomainUnit
        val dy = (end.y - start.y) / safeRangeUnit
        val distance = hypot(dx, dy).coerceAtLeast(0.000_001)
        val normalX = -dy / distance
        val normalY = dx / distance
        val curveSign = if (dx < 0.0) -1.0 else 1.0
        val curve = distance * CURVE_SHARE * curveSign
        val offsetX = (normalX * curve * safeDomainUnit)
            .coerceIn(-safeDomainUnit * MAX_DOMAIN_BEND_UNITS, safeDomainUnit * MAX_DOMAIN_BEND_UNITS)
        val offsetY = (normalY * curve * safeRangeUnit)
            .coerceIn(-safeRangeUnit * MAX_RANGE_BEND_SHARE, safeRangeUnit * MAX_RANGE_BEND_SHARE)
        return Geometry(
            start,
            Point(
                start.x + (dx * CONTROL_NEAR * safeDomainUnit) + offsetX,
                start.y + (dy * CONTROL_NEAR * safeRangeUnit) + offsetY
            ),
            Point(
                start.x + (dx * CONTROL_FAR * safeDomainUnit) + offsetX,
                start.y + (dy * CONTROL_FAR * safeRangeUnit) + offsetY
            ),
            end,
            safeDomainUnit,
            safeRangeUnit
        )
    }

    private fun edgePoint(
        bounds: Bounds,
        targetX: Double,
        targetY: Double,
        domainUnit: Double,
        rangeUnit: Double
    ): Point {
        val dx = (targetX - bounds.centerX) / domainUnit
        val dy = (targetY - bounds.centerY) / rangeUnit
        val halfWidth = (bounds.width / domainUnit / 2.0).coerceAtLeast(0.000_001)
        val halfHeight = (bounds.height / rangeUnit / 2.0).coerceAtLeast(0.000_001)
        val boundaryScale = max(abs(dx) / halfWidth, abs(dy) / halfHeight).coerceAtLeast(1.0)
        return Point(
            bounds.centerX + ((dx / boundaryScale) * domainUnit),
            bounds.centerY + ((dy / boundaryScale) * rangeUnit)
        )
    }

    private fun fastener(point: Point, domainUnit: Double, rangeUnit: Double): List<XYAnnotation> {
        val outerWidth = domainUnit * FASTENER_DOMAIN_SIZE
        val outerHeight = rangeUnit * FASTENER_RANGE_SIZE
        val outer = Ellipse2D.Double(
            point.x - outerWidth / 2.0,
            point.y - outerHeight / 2.0,
            outerWidth,
            outerHeight
        )
        val shine = Ellipse2D.Double(
            point.x - outerWidth * 0.20,
            point.y + outerHeight * 0.06,
            outerWidth * 0.34,
            outerHeight * 0.34
        )
        return listOf(
            BrokerTradeShapeAnnotation(outer, FASTENER_STROKE, FASTENER_BORDER, FASTENER_FILL),
            BrokerTradeShapeAnnotation(shine, BasicStroke(0.4f), FASTENER_SHINE, FASTENER_SHINE)
        )
    }

    internal data class Bounds(val left: Double, val bottom: Double, val right: Double, val top: Double) {
        val centerX: Double get() = (left + right) / 2.0
        val centerY: Double get() = (bottom + top) / 2.0
        val width: Double get() = right - left
        val height: Double get() = top - bottom
    }

    internal data class Point(val x: Double, val y: Double)
    internal data class Geometry(
        val start: Point,
        val control1: Point,
        val control2: Point,
        val end: Point,
        val domainUnit: Double,
        val rangeUnit: Double
    )

    private val CONNECTOR_COLOR = Color(112, 78, 160, 220)
    private val FASTENER_BORDER = Color(67, 47, 99, 240)
    private val FASTENER_FILL = Color(132, 96, 181, 235)
    private val FASTENER_SHINE = Color(235, 225, 249, 235)
    private val CONNECTOR_STROKE = BasicStroke(1.45f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    private val FASTENER_STROKE = BasicStroke(1.0f)
    private const val CONTROL_NEAR = 0.24
    private const val CONTROL_FAR = 0.76
    private const val CURVE_SHARE = 0.24
    private const val MAX_DOMAIN_BEND_UNITS = 2.0
    private const val MAX_RANGE_BEND_SHARE = 0.04
    private const val FASTENER_DOMAIN_SIZE = 0.42
    private const val FASTENER_RANGE_SIZE = 0.026
}
