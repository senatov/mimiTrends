package org.senatov.mimitrends.charts

private const val CARD_WIDTH_BARS = 10.0
private const val MIN_CARD_DOMAIN_SHARE = 0.16
private const val MAX_CARD_DOMAIN_SHARE = 0.90
private const val CARD_HEIGHT_SHARE = 0.07
private const val MAX_CARD_RANGE_SHARE = 0.12
private const val CARD_EDGE_PADDING_BARS = 0.5
private const val CARD_EDGE_PADDING_SHARE = 0.02
private const val CARD_FLIP_GAP = 0.04
private const val CARD_LEVEL_GAP = 0.02
private const val MAX_HORIZONTAL_OFFSETS = 3
private const val MAX_VERTICAL_LEVELS = 5

internal object TradeCardLayout {
    fun place(
        preferredX: Double,
        preferredBottom: Double,
        timeStep: Double,
        priceSpan: Double,
        domainMin: Double,
        domainMax: Double,
        rangeMin: Double,
        rangeMax: Double,
        occupied: List<BrokerTradeAnnotations.CardBounds>
    ): BrokerTradeAnnotations.CardBounds {
        val initial = bounds(
            preferredX, preferredBottom, timeStep, priceSpan,
            domainMin, domainMax, rangeMin, rangeMax
        )
        if (occupied.none { overlaps(initial, it) }) return initial
        val horizontalStep = initial.width + timeStep
        val verticalStep = initial.height + priceSpan * CARD_LEVEL_GAP
        val candidates = buildList {
            val verticalOffsets = listOf(0) + (1..MAX_VERTICAL_LEVELS).flatMap { listOf(-it, it) }
            for (level in verticalOffsets) {
                val bottom = preferredBottom + level * verticalStep
                add(bounds(preferredX, bottom, timeStep, priceSpan, domainMin, domainMax, rangeMin, rangeMax))
                for (offset in 1..MAX_HORIZONTAL_OFFSETS) {
                    add(bounds(preferredX + offset * horizontalStep, bottom, timeStep, priceSpan,
                        domainMin, domainMax, rangeMin, rangeMax))
                    add(bounds(preferredX - offset * horizontalStep, bottom, timeStep, priceSpan,
                        domainMin, domainMax, rangeMin, rangeMax))
                }
            }
        }.distinct()
        return candidates.firstOrNull { candidate -> occupied.none { overlaps(candidate, it) } } ?: initial
    }

    fun bounds(
        preferredX: Double,
        preferredBottom: Double,
        timeStep: Double,
        priceSpan: Double,
        domainMin: Double,
        domainMax: Double,
        rangeMin: Double,
        rangeMax: Double
    ): BrokerTradeAnnotations.CardBounds {
        val domainSpan = (domainMax - domainMin).coerceAtLeast(timeStep)
        val width = maxOf(timeStep * CARD_WIDTH_BARS, domainSpan * MIN_CARD_DOMAIN_SHARE)
            .coerceAtMost(domainSpan * MAX_CARD_DOMAIN_SHARE)
        val height = minOf(priceSpan * CARD_HEIGHT_SHARE, (rangeMax - rangeMin) * MAX_CARD_RANGE_SHARE)
        val horizontalPadding = minOf(timeStep * CARD_EDGE_PADDING_BARS, domainSpan * 0.02)
        val minCenter = domainMin + horizontalPadding + width / 2.0
        val maxCenter = domainMax - horizontalPadding - width / 2.0
        val centerX = if (minCenter <= maxCenter) preferredX.coerceIn(minCenter, maxCenter)
        else (domainMin + domainMax) / 2.0
        val verticalPadding = priceSpan * CARD_EDGE_PADDING_SHARE
        val topLimit = rangeMax - verticalPadding
        val bottomLimit = rangeMin + verticalPadding
        val bottom = when {
            preferredBottom + height <= topLimit -> preferredBottom
            preferredBottom - height - priceSpan * CARD_FLIP_GAP >= bottomLimit ->
                preferredBottom - height - priceSpan * CARD_FLIP_GAP
            else -> (topLimit - height).coerceAtLeast(bottomLimit)
        }
        return BrokerTradeAnnotations.CardBounds(
            centerX - width / 2.0, bottom, centerX + width / 2.0, bottom + height
        )
    }

    private fun overlaps(
        first: BrokerTradeAnnotations.CardBounds,
        second: BrokerTradeAnnotations.CardBounds
    ): Boolean = first.left < second.right && first.right > second.left &&
        first.bottom < second.top && first.top > second.bottom
}
