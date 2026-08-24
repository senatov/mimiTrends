package org.senatov.mimitrends.charts

import org.jfree.chart.annotations.XYShapeAnnotation
import java.awt.Paint
import java.awt.Shape
import java.awt.Stroke

internal interface BrokerTradeOwnedAnnotation

internal class BrokerTradeShapeAnnotation(
    shape: Shape,
    stroke: Stroke,
    outlinePaint: Paint,
    fillPaint: Paint? = null
) : XYShapeAnnotation(shape, stroke, outlinePaint, fillPaint), BrokerTradeOwnedAnnotation
